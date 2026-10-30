#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

mountpoint -q /mnt/candybear/store || { echo remote_storage_not_mounted >&2; exit 3; }
key=/root/.config/candybear/backup.key
test -s "$key" || { echo backup_encryption_key_is_missing >&2; exit 7; }
temps=()
cleanup() { rm -f -- "${temps[@]}"; }
trap cleanup EXIT

encrypt_one() {
    local source=$1 final="$1.enc" part="$1.enc.part"
    local manifest_part="$1.enc.sha256.part" verify
    verify=$(mktemp /run/candybear-legacy-verify.XXXXXX)
    temps+=("$part" "$manifest_part" "$verify")
    openssl enc -aes-256-cbc -salt -pbkdf2 -iter 200000 -pass "file:$key" \
        -in "$source" | tee "$part" | sha256sum \
        | awk -v name="$(basename "$final")" '{print $1 "  " name}' > "$manifest_part"
    openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass "file:$key" \
        -in "$part" -out "$verify"
    cmp -s -- "$source" "$verify" || { echo encrypted_backup_verification_failed >&2; return 8; }
    case "$source" in
        *.tar.gz) gzip -t "$verify"; tar -tzf "$verify" >/dev/null ;;
        *.sql.gz) gzip -t "$verify" ;;
    esac
    mv -T -- "$part" "$final"
    mv -T -- "$manifest_part" "$final.sha256"
    rm -f -- "$source" "$source.sha256"
    rm -f -- "$verify"
    printf 'encrypted=%s\n' "$(basename "$final")"
}

shopt -s nullglob
for source in /mnt/candybear/store/backups/database/*.sql.gz \
              /mnt/candybear/store/backups/config/*.tar.gz; do
    [[ -f "$source" && ! -e "$source.enc" ]] || continue
    encrypt_one "$source"
done
