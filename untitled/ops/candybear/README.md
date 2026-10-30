# CandyBear Storage Policy

- MySQL remains on 88. Daily dumps are encrypted and stored in `225:/backups/database`; keep the 7 newest daily snapshots and 4 newest weekly snapshots. A run stops when 225 has less than 1 GiB free.
- The weekly recovery archive contains the JAR, runtime configuration, sticker index, systemd units, log rotation policy, and QQ profile directory. It is encrypted before it reaches 225.
- The AES key is `/root/.config/candybear/backup.key` on 88 and `C:\Users\35243\.ssh\candybear-backup.key` on the operator workstation. Keep the workstation copy in a separate protected backup. Losing both copies makes the encrypted archives unrecoverable.
- Generated media, TTS files, and sticker images write directly to `/mnt/candybear/store/media/{generated,tts,stickers}`. They are not automatically deleted.
- CandyBear logs stay on 88 while active. Daily rotation keeps up to 7 archives in `225:/backups/logs`; the newest rotated file remains uncompressed for one cycle, then compresses. Rotation is skipped if the remote mount is unavailable.
- The daily cleanup removes only `/tmp/screenshot_*.png` and `/tmp/debug-*.png` older than 7 days. It does not clean QQ data, browser caches, or arbitrary `/tmp` files.
- `candybear-db-backup.timer` and `candybear-clean-temp.timer` are enabled. CandyBear and NapCat remain disabled until the QQ profile is migrated and cutover is approved.

Run a database backup with `systemctl start candybear-db-backup.service`. Force a fresh weekly recovery archive with `/opt/qq-bot/backup-db-to-225.sh --force-config`.

To restore an encrypted database archive, decrypt it with the key, then run `gzip -t` on the resulting `.sql.gz` before importing it. The config archive decrypts to a gzip tar file; check it with `gzip -t` and `tar -tzf` before extracting. Do not place the key on 225 or in this repository.
