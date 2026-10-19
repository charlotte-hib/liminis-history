#!/usr/bin/env bash
# Pulls a backup of the review's database from the server to the curator's computer: pg_dump in the database's own
# container (custom format, zstd), over Tailscale SSH, into BACKUP_DIR with its sha256 beside it; keeps the newest 7.
# It holds the curators' decisions and auth's signing keys: keep BACKUP_DIR on an encrypted disk. Run nightly by
# launchd on the curator's computer:
#   BACKUP_SERVER=<deploy user>@<server> deploy/backup-review.sh
# Restore: pg_restore --clean --if-exists --no-owner into the review's database, from a one-off container of its image.
set -euo pipefail

server="${BACKUP_SERVER:?BACKUP_SERVER=user@host}"
dir="${BACKUP_DIR:-$HOME/liminis-backups}"
name="review-$(date +%Y-%m-%d)"
keep=7

mkdir -p "$dir"
cd "$dir"
# Under a hidden name until it is whole, so a cut transfer never looks like a backup.
ssh "$server" "cd /opt/liminis && docker compose -f compose.review.yaml --env-file review.env exec -T db \
  sh -c 'pg_dump --username=\"\$POSTGRES_USER\" --dbname=\"\$POSTGRES_DB\" --format=custom --compress=zstd'" > ".$name.dump"
[[ $(head -c 5 ".$name.dump") == PGDMP ]] || { echo "backup-review: not a pg_dump archive" >&2; exit 1; }
mv ".$name.dump" "$name.dump"
shasum -a 256 "$name.dump" > "$name.dump.sha256"

ls -t review-*.dump | tail -n +$((keep + 1)) | while read -r old; do rm -f "$old" "$old.sha256"; done
echo "Backed up $(du -h "$name.dump" | cut -f1) to $dir/$name.dump"
