#!/usr/bin/env bash
# Restores only into empty recovery targets; it never overwrites a live instance.
set -euo pipefail
backup_dir="${1:?Usage: restore-photosync.sh /path/to/photosync-backup}"
compose_file="${PHOTOSYNC_COMPOSE_FILE:-compose.yml}"
media_root="${PHOTOSYNC_RESTORE_MEDIA_ROOT:?Set an EMPTY recovery media directory}"
expected_fstype="${PHOTOSYNC_EXPECTED_STORAGE_FSTYPE:-vboxsf}"
require() { command -v "$1" >/dev/null || { echo "Missing required command: $1" >&2; exit 1; }; }
for command in docker findmnt rsync sha256sum; do require "$command"; done
[[ -f "$backup_dir/manifest.env" && -f "$backup_dir/SHA256SUMS" ]] || { echo "Not a PhotoSync backup" >&2; exit 1; }
actual_fstype="$(findmnt -T "$media_root" -n -o FSTYPE || true)"
[[ "$actual_fstype" == "$expected_fstype" ]] || { echo "Recovery mount has unexpected filesystem: $actual_fstype" >&2; exit 1; }
[[ -d "$media_root" ]] || { echo "Recovery media root does not exist" >&2; exit 1; }
[[ -z "$(find "$media_root" -mindepth 1 -print -quit)" ]] || { echo "Recovery media root must be empty" >&2; exit 1; }
(cd "$backup_dir" && sha256sum -c SHA256SUMS)
app_id="$(docker compose -f "$compose_file" ps --all -q app)"
[[ -n "$app_id" ]] || { echo "PhotoSync app container was not found" >&2; exit 1; }
[[ "$(docker inspect -f '{{.State.Running}}' "$app_id")" == "false" ]] || { echo "Stop the app before recovery" >&2; exit 1; }
docker compose -f "$compose_file" run --rm --no-deps --entrypoint sh app -c 'test -z "$(find /data/system -mindepth 1 -maxdepth 1 -print -quit)"' || { echo "Refusing restore: the target SQLite directory is not empty" >&2; exit 1; }
rsync -a --no-owner --no-group "$backup_dir/media/" "$media_root/"
docker compose -f "$compose_file" run --rm --no-deps -v "$backup_dir/db:/backup:ro" --entrypoint sh app -c 'cp -a /backup/. /data/system/'
echo "Recovery data staged. Keep the app stopped; validate the recovery instance before cutover."
