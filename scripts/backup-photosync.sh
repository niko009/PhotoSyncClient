#!/usr/bin/env bash
# Creates a stopped-application snapshot of PhotoSync originals and SQLite.
set -euo pipefail
compose_file="${PHOTOSYNC_COMPOSE_FILE:-compose.yml}"
media_root="${PHOTOSYNC_MEDIA_ROOT:-/mnt/server}"
backup_root="${PHOTOSYNC_BACKUP_ROOT:?Set PHOTOSYNC_BACKUP_ROOT to an off-host backup destination}"
expected_fstype="${PHOTOSYNC_EXPECTED_STORAGE_FSTYPE:-vboxsf}"
require() { command -v "$1" >/dev/null || { echo "Missing required command: $1" >&2; exit 1; }; }
for command in docker findmnt rsync sha256sum find sort xargs; do require "$command"; done
actual_fstype="$(findmnt -T "$media_root" -n -o FSTYPE || true)"
[[ "$actual_fstype" == "$expected_fstype" ]] || { echo "Refusing backup: $media_root is '$actual_fstype', expected '$expected_fstype'." >&2; exit 1; }
mkdir -p "$backup_root"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
final_dir="$backup_root/photosync-$stamp"
temporary_dir="$backup_root/.photosync-$stamp.incomplete"
[[ ! -e "$final_dir" && ! -e "$temporary_dir" ]] || { echo "Backup destination already exists" >&2; exit 1; }
mkdir -p "$temporary_dir/db" "$temporary_dir/media"
app_id="$(docker compose -f "$compose_file" ps --all -q app)"
[[ -n "$app_id" ]] || { echo "PhotoSync app container was not found" >&2; exit 1; }
was_running="$(docker inspect -f '{{.State.Running}}' "$app_id")"
restart_app() { if [[ "$was_running" == "true" ]]; then docker compose -f "$compose_file" start app; fi; }
trap restart_app EXIT
if [[ "$was_running" == "true" ]]; then docker compose -f "$compose_file" stop app; fi
# Copy DB, WAL and SHM together while SQLite has no running writer.
docker cp "$app_id:/data/system/." "$temporary_dir/db"
rsync -a --no-owner --no-group "$media_root/" "$temporary_dir/media/"
(
  cd "$temporary_dir"
  find db media -type f -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
  cat > manifest.env <<EOF
created_utc=$stamp
media_root=$media_root
media_fstype=$actual_fstype
compose_file=$compose_file
EOF
)
mv "$temporary_dir" "$final_dir"
echo "Backup completed: $final_dir"
