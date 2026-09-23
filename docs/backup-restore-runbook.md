# Backup and recovery runbook

This runbook covers the current topology: originals under the VirtualBox
`vboxsf` mount `/mnt/server`, SQLite in the `bacus-photosync-data` Docker volume,
and the Compose app named `app`. A Windows shared folder or Docker volume alone is
not a backup.

## Preconditions

- Keep backups off the Windows disk and off the Ubuntu VM disk.
- Install `rsync`, `sha256sum`, `findmnt`, and Docker Compose on the Ubuntu host.
- Run from the repository root. The scripts refuse a non-`vboxsf` `/mnt/server`.
- Before the first production start, create the persistent mount marker exactly once:
  `printf 'photosync-storage-v1\n' | sudo tee /mnt/server/.photosync-storage-root >/dev/null`.
  The service fails closed if that marker or its storage write/read probe is absent.
- A backup stops a running app for a consistent SQLite and originals snapshot,
  then restarts it even when copying fails. In-flight Android uploads retry.

## Create a backup

```bash
export PHOTOSYNC_BACKUP_ROOT=/path/on/off-host-backup
bash ./scripts/backup-photosync.sh
```

The result contains `db/`, `media/`, `SHA256SUMS`, and topology metadata. It is
sensitive personal data: encrypt it through the backup system, set retention
there, and never commit it. Retain a known-good backup before server changes.

## Test recovery

Recovery is intentionally restricted to an empty recovery media mount and an
empty Docker data volume; it cannot overwrite a live instance. Prepare a separate
Compose project/volume and an empty `vboxsf` recovery mount, keep its app stopped,
then run:

```bash
export PHOTOSYNC_COMPOSE_FILE=/path/to/recovery/compose.yml
export PHOTOSYNC_RESTORE_MEDIA_ROOT=/mnt/photosync-recovery
bash ./scripts/restore-photosync.sh /path/on/off-host-backup/photosync-YYYYMMDDTHHMMSSZ
```

The script verifies every file before staging. Start only the isolated recovery
app afterward, authenticate with a non-destructive test device, check expected
albums/files, and compare an original hash. Record backup timestamp, restore
duration, and result.

## Production incident recovery

Never point the restore script at live `/mnt/server` or an existing
`bacus-photosync-data` volume. Restore and validate in isolation first. A live
cutover needs an approved maintenance procedure: stop traffic, preserve failed
storage unchanged, replace storage with validated recovery targets, start the
app, inspect Docker health, and verify an authenticated upload/download checksum.
There is no automated rollback; retain failed storage until recovery is accepted.

## Health and reboot checks

After a host/VM reboot, first verify `findmnt -T /mnt/server` reports `vboxsf`,
then start Compose and wait for `docker compose ps` to show `healthy`. Docker's
health check calls `/health`, which verifies SQLite and the storage marker/write
probe. The mount check remains mandatory before admitting uploads.
