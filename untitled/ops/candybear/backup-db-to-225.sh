#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

source /opt/qq-bot/.env
test -n "${DB_URL:-}" || { echo DB_URL_is_missing >&2; exit 2; }
test -n "${DB_USER:-}" || { echo DB_USER_is_missing >&2; exit 2; }
test -n "${DB_PASSWORD:-}" || { echo DB_PASSWORD_is_missing >&2; exit 2; }
[[ "${1:-}" == "" || "${1:-}" == "--force-config" ]] || exit 2
force_config=${1:-}
encryption_key=/root/.config/candybear/backup.key
test -s "$encryption_key" || { echo backup_encryption_key_is_missing >&2; exit 7; }

database=$(printf '%s\n' "$DB_URL" | sed -E 's#^jdbc:mysql://[^/]+/([^?]+).*#\1#')
[[ "$database" =~ ^[A-Za-z0-9_]+$ ]] || { echo invalid_database_name >&2; exit 2; }
mountpoint -q /mnt/candybear/store || { echo remote_storage_not_mounted >&2; exit 3; }

remote_root=/mnt/candybear/store/backups
db_dir=$remote_root/database
config_dir=$remote_root/config
install -d -m 700 "$db_dir" "$config_dir"
chmod 700 "$db_dir" "$config_dir"
available_bytes=$(df -B1 --output=avail "$remote_root" | tail -n 1 | tr -d ' ')
[[ "$available_bytes" =~ ^[0-9]+$ ]] || { echo remote_free_space_unknown >&2; exit 5; }
if (( available_bytes < 1073741824 )); then echo remote_storage_below_1GiB_free >&2; exit 6; fi

client_defaults=$(mktemp /run/candybear-db-client.XXXXXX)
plain_db=$(mktemp /run/candybear-db-plain.XXXXXX)
temps=("$client_defaults" "$plain_db")
cleanup() { rm -f -- "${temps[@]}"; }
trap cleanup EXIT
export DB_CLIENT_CNF="$client_defaults"
python3 - <<'PY'
import os
from pathlib import Path

def quote(value):
    if '\n' in value or '\r' in value:
        raise SystemExit('newline in database option')
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'

Path(os.environ['DB_CLIENT_CNF']).write_text('\n'.join([
    '[client]',
    'protocol=tcp',
    'host=127.0.0.1',
    'port=3306',
    'user=' + quote(os.environ['DB_USER']),
    'password=' + quote(os.environ['DB_PASSWORD']),
]) + '\n', encoding='utf-8')
PY
chmod 600 "$client_defaults"

write_manifest() {
    local file=$1
    local manifest_tmp="$file.sha256.part"
    temps+=("$manifest_tmp")
    (cd "$(dirname "$file")" && sha256sum "$(basename "$file")") > "$manifest_tmp"
    mv -T -- "$manifest_tmp" "$file.sha256"
}

encrypt_backup() {
    local source=$1 final=$2
    local part="$final.part"
    local verify
    verify=$(mktemp /run/candybear-backup-verify.XXXXXX)
    temps+=("$part" "$verify")
    openssl enc -aes-256-cbc -salt -pbkdf2 -iter 200000 \
        -pass "file:$encryption_key" -in "$source" -out "$part"
    openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 \
        -pass "file:$encryption_key" -in "$part" -out "$verify"
    cmp -s -- "$source" "$verify" || { echo encrypted_backup_verification_failed >&2; return 8; }
    mv -T -- "$part" "$final"
    write_manifest "$final"
}

prune_exact() {
    local directory=$1 pattern=$2 retain=$3
    local -a files=()
    mapfile -t files < <(find "$directory" -maxdepth 1 -type f -name "$pattern" -printf '%f\n' | sort -r)
    for old in "${files[@]:$retain}"; do
        rm -f -- "$directory/$old" "$directory/$old.sha256"
    done
}

stamp=$(date -u +%Y%m%dT%H%M%S)
db_name="candybear_db_88_${stamp}.sql.gz.enc"
mysqldump --defaults-extra-file="$client_defaults" --single-transaction --quick \
    --routines --triggers --events --no-tablespaces --set-gtid-purged=OFF "$database" \
    | gzip -c > "$plain_db"
gzip -t "$plain_db"
encrypt_backup "$plain_db" "$db_dir/$db_name"
printf 'database_backup=%s\n' "$db_name"
stat -c 'database_size=%s bytes' "$db_dir/$db_name"

if [[ "$(date -u +%u)" == 7 ]]; then
    weekly_db_name="candybear_db_88_weekly_$(date -u +%G-%V).sql.gz.enc"
    encrypt_backup "$plain_db" "$db_dir/$weekly_db_name"
fi

if [[ "$(date -u +%u)" == 7 || "$force_config" == "--force-config" ]]; then
    config_plain=$(mktemp /run/candybear-config-plain.XXXXXX)
    temps+=("$config_plain")
    config_name="candybear_config_88_weekly_$(date -u +%G-%V).tar.gz.enc"
    tar -C / -czf "$config_plain" \
        opt/qq-bot/.env \
        opt/qq-bot/application.properties \
        opt/qq-bot/run.sh \
        opt/qq-bot/backup-db-to-225.sh \
        opt/qq-bot/untitled-1.0-SNAPSHOT.jar \
        opt/qq-bot/data \
        opt/qq-bot/screenshot.py \
        etc/systemd/system/candybear.service \
        etc/systemd/system/napcat.service \
        etc/systemd/system/candybear-db-backup.service \
        etc/systemd/system/candybear-db-backup.timer \
        etc/systemd/system/candybear-clean-temp.service \
        etc/systemd/system/candybear-clean-temp.timer \
        etc/systemd/system/mnt-candybear-store.mount \
        etc/logrotate.d/candybear \
        usr/local/sbin/candybear-clean-temp.sh \
        home/alice/.config/QQ
    gzip -t "$config_plain"
    encrypt_backup "$config_plain" "$config_dir/$config_name"
    printf 'config_backup=%s\n' "$config_name"
    stat -c 'config_size=%s bytes' "$config_dir/$config_name"
fi

prune_exact "$db_dir" 'candybear_db_88_????????T??????.sql.gz.enc' 7
prune_exact "$db_dir" 'candybear_db_88_weekly_????-??.sql.gz.enc' 4
prune_exact "$config_dir" 'candybear_config_88_weekly_????-??.tar.gz.enc' 4
printf 'retention=7_daily_4_weekly; encrypted=yes; media=never_auto_delete\n'
