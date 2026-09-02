# Offline-first sync

PhotoSync is local-first: users must be able to create folders and add photos/videos without Internet access. Server availability is not a prerequisite for accepting local work.

## Required behavior

1. Creating a folder always creates the local folder immediately.
2. If the server is unavailable, the folder remains local/pending rather than failing or disappearing.
3. Adding media first creates a durable local queue entry.
4. Queued media is shown as `Pending` and counts toward the folder's pending total.
5. PhotoSync synchronizes in dependency order when connectivity returns:
   - register/refresh the device;
   - create any server folders that were created offline;
   - upload queued media into those folders;
   - refresh server state.
6. Upload retries use the same queued item and server SHA-256 deduplication, so uncertain/repeated attempts must not create duplicate server originals.
7. Server-side photo/video originals are never physically deleted by retry or queue cleanup.

## Durable media access

For document-picker URIs PhotoSync retains persistable read access when Android allows it. Share-sheet URIs often do not provide a durable permission; in that case PhotoSync copies the incoming media into app-private `offline_queue` storage before considering the add operation successful. This lets queued uploads survive process death and long offline periods.

The temporary app-private queue copy is not the server original. Once an item is synchronized, the server-backed item is authoritative for backup purposes.

## Automatic synchronization

There are three recovery paths:

- **Foreground/process alive:** `NetworkSyncObserver` listens for validated Internet connectivity and triggers repository refresh/sync.
- **App restart:** repository initialization refreshes server state and resumes pending work.
- **App closed / process killed:** `OfflineSyncScheduler` uses Android WorkManager with a connected-network constraint. The work request survives process death and device reboot and retries when the server is still unavailable.

WorkManager is scheduled whenever a folder or media item is accepted locally while synchronization may still be pending.

## Compatibility with older builds

`RetryingPhotoSyncRepository` remains as a compatibility layer for `Failed`, `Pending`, or stale `Uploading` records produced by older PhotoSync versions. It retries legacy records only after the server reports an online connection. New offline media uses `OfflineFirstPhotoSyncRepository` instead of intentionally creating `Failed` records.

## UX semantics

- `Pending` means the local item is safely queued and still needs server synchronization.
- `Synced` means the server accepted the item.
- `Failed` is reserved for legacy/permanent failures rather than ordinary lack of Internet.
- Adding media offline should return success to the UI once durable local queuing succeeds.

## 0.6.1-beta

Offline-first folders/media, automatic connectivity-triggered synchronization, persistent WorkManager background retry, legacy upload recovery, and in-app updates are part of the 0.6.1-beta client changes. A release-signed APK still requires the same permanent signing certificate as 0.6.0-beta for an in-place Android update.
