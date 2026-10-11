# Offline-first sync

PhotoSync is local-first: users must be able to create folders and add photos/videos without Internet access. Server availability is not a prerequisite for accepting local work.

## Required behavior

1. Creating a folder always creates the local folder immediately.
2. If the server is unavailable, the folder remains local/pending rather than failing or disappearing.
3. Adding media stages the entire selected batch sequentially, then atomically publishes its durable queue entries.
4. Queued media is shown as `Pending` and counts toward the folder's pending total.
5. PhotoSync synchronizes in dependency order when connectivity returns:
   - register/refresh the device;
   - create any server folders that were created offline;
   - upload queued media into those folders;
   - refresh server state.
6. Upload retries use the same queued item and server SHA-256 deduplication, so uncertain/repeated attempts must not create duplicate server originals.
7. The queue persists the original source URI separately from its app-private staged upload file, so post-sync cleanup targets the selected device original rather than the staging copy.
8. App-private staged files are removed after a successful upload; server-side photo/video originals are never physically deleted by retry or queue cleanup.

## Durable media access

For Android Photo Picker URIs PhotoSync retains persistable read access when Android allows it. Share-sheet URIs often do not provide a durable permission; PhotoSync always copies incoming media into app-private `offline_queue` storage before considering the add operation successful. This lets queued uploads survive process death and long offline periods while retaining the original URI for post-sync cleanup.

The temporary app-private queue copy is not the server original. Once an item is synchronized, the server-backed item is authoritative for backup purposes.

## Automatic synchronization

There are three recovery paths:

- **Foreground/process alive:** `NetworkSyncObserver` listens for default-network changes and triggers refresh/sync only when the shared media network policy permits it.
- **App restart:** repository initialization refreshes server state and resumes pending work.
- **App closed / process killed:** `OfflineSyncScheduler` uses Android WorkManager with an UNMETERED constraint by default (CONNECTED only after explicit mobile-data opt-in), plus the shared runtime transport check. The work request survives process death and device reboot and retries when the server is still unavailable.

WorkManager is scheduled once per committed batch (or local folder), never per item during preparation. Changing the mobile setting replaces pending work with the new constraints.

## Compatibility with older builds

`RetryingPhotoSyncRepository` was removed in 0.7.9. The offline repository adopts legacy Pending/Failed/Uploading rows into its durable queue, matches source/staged URIs against existing ownership, and removes old local rows only after queue persistence. Missing sources become visible terminal failures. Legacy cleanup defaults to Keep because old rows did not capture the enqueue-time setting. Deterministic known terminal failure codes remain terminal.

## UX semantics

- `Pending` means the local item is safely queued and still needs server synchronization.
- `Synced` means the server accepted the item.
- `Failed` is reserved for legacy/permanent failures rather than ordinary lack of Internet.
- Adding media offline should return success to the UI once durable local queuing succeeds.

## 0.6.1-beta

Offline-first folders/media, automatic connectivity-triggered synchronization, persistent WorkManager background retry, legacy upload recovery, and in-app updates are part of the 0.6.1-beta client changes. A release-signed APK still requires the same permanent signing certificate as 0.6.0-beta for an in-place Android update.

## Batch transaction and network policy (0.7.9)

`enqueueMedia(folderId, uris)` and `enqueueSharedMedia` share an atomic batch
preparer. Duplicate source URIs already queued for that folder are omitted. A
preparation journal identifies one private staging directory; source bytes are
streamed one file at a time on Dispatchers.IO, with fsync. The repository mutex
holds older sync work behind preparation as well. The staging journal is not a
worker queue: only the final single SharedPreferences commit publishes the batch.
A non-cancellable commit/publication/scheduling section closes the cancellation
window. No SHA pass is added to preparation; the existing protocol fingerprints
the staged file before upload.

Any preparation failure rejects the whole new batch, preserves older queued work,
removes uncommitted copies and reports Failed. Users must reselect that batch.
Restart detects an unfinished journal and rolls it back with a persisted failure
marker; a journal left after a successful commit never deletes committed copies.
Cancellation follows the same atomic rollback rules. Committed files and queue
records survive reboot/process death. Picker maximum now comes from AndroidX /
the platform rather than a hardcoded 50.

`AndroidSyncNetworkAccess` is the single evaluator. Missing preference means
WifiOnly on fresh installation and upgrade. WifiOnly requires validated Internet,
Wi-Fi transport and no cellular/VPN ambiguity. AnyNetwork requires explicit
mobile-data opt-in and permits other validated transports too. No network policy
blocks local staging. The UI distinguishes preparation, waiting (including
Waiting for Wi-Fi), synchronization, failure and completion. Strings are available
in English, Russian and Romanian.

WorkManager UNMETERED is a conservative approximation, not proof of Wi-Fi. Every
worker and every media transfer rechecks transport. Unmetered cellular still
cannot upload by default. Foreground / a live connectivity observer accepts
validated metered Wi-Fi, but a closed app's default worker waits for unmetered
Wi-Fi. VPN/ambiguous default transport is blocked in WifiOnly; explicit AnyNetwork
permits it. These deliberate restrictions prefer avoiding cellular charges.

Every payload connection uses Android Network.openConnection, pinning it to the
approved Network. Checks before chunks and each 64 KiB write pause on policy or
network change. The socket cannot migrate to a new cellular default. A policy
pause stays Pending, without failure count or retry penalty. A later attempt
reopens the resumable session and trusts only server-confirmed offsets. The
server's all-or-nothing chunk protocol now requires an exact chunk-end ACK;
invalid/partial ACKs become terminal protocol errors, not transfer loops.

See [release audit and validation](release-0.7.9.md).
