# PhotoSync 0.7.9-beta (7009)

Issues: [#3](https://github.com/niko009/PhotoSyncClient/issues/3),
[#4](https://github.com/niko009/PhotoSyncClient/issues/4).

## ROOT CAUSE #3

The picker called uploadToFolder for each URI. That method held the queue mutex
through staging, persistence and immediate network upload, serializing the next
file's preparation behind the previous upload. The hardcoded picker limit was 50.

Both picker and share now call a batch preparer: deduplicate, resolve cleanup
once, copy sequentially off the main thread, fsync, commit the full queue once,
then schedule once and sync if allowed. The mutex also blocks existing worker
sync during preparation. Atomic rollback and an interrupted-preparation journal
make failure/cancellation explicit; previously committed work is retained.
Preparation failure requires reselecting the complete rejected batch. Gallery
Share retains the live Activity for cleanup confirmation when sync is allowed;
offline/cellular staging succeeds without attempting payload upload.

## ROOT CAUSE OF CELLULAR USAGE

Confirmed in the previous source: WorkManager used CONNECTED; the observer
accepted any validated network; the offline repository accepted any active
network capabilities. No persisted mobile-data consent or payload-level transport
guard existed. Google linking/restoration used those same unrestricted paths.
These are sufficient to explain why cellular uploads were possible, independently
of why the incident transferred approximately 15 GB.

## POSSIBLE / CONFIRMED CAUSE OF EXCESS RETRANSMISSION

Confirmed code-level ownership defect: the offline repository wrapped a legacy
retry repository whose refresh retried every unsynced Pending/Failed/Uploading
local URI. A failed durable upload could leave such a local row. Hiding that row
in UI did not exclude it from legacy retry. The legacy path could run before the
durable queue, bypass its persisted retry deadline, use the source URI rather
than the staged copy, and then allow the durable path to attempt the same media.
Server SHA deduplication prevented duplicate originals; it did not prove that no
payload was retransmitted after uncertain chunks. There is no evidence here that
this overlap specifically caused the historical 15 GB.

The wrapper is removed. Legacy rows are adopted into the durable queue, with
explicit source/staged URI matching and one mutex/backoff owner. Adoption commits
before retiring old local rows. Unreadable sources and known permanent failure
codes remain visible terminal failures; legacy cleanup defaults to Keep.

The API still permits two bounded transient retries (1 s / 2 s or a short
Retry-After). Each attempt starts/resumes the session keyed by device/album/SHA,
using its server-confirmed offset. WorkManager and observer call the same queue;
item cooldowns survive restart, and 429 pauses the queue. Policy pauses do not
consume retry count or become Failed. Lost completion responses resolve through
completed_file without sending the payload again.

A separate potential amplification mechanism was found: the PUT loop accepted
any positive partial ACK after a full chunk. A peer confirming only a few bytes
could cause repeated large overlapping sends. The current server rolls incomplete
chunks back and commits the whole fixed-length body, so this was not evidence of
an actual production partial ACK. The client now requires exact chunk-end ACKs;
invalid offsets are terminal UPLOAD_PROTOCOL_ERROR failures.

Historical 15 GB traffic cannot be reconstructed or attributed to any one retry
path without device/server logs. It is explicitly not claimed as proven.

## Network policy and handover

A persisted WifiOnly/AnyNetwork policy is evaluated by AndroidSyncNetworkAccess.
Absent preference is WifiOnly, on installation and upgrade. The settings switch
is OFF by default and is localized in EN/RU/RO. Local selection/staging always
works offline/on cellular. Observer events are conflated instead of cancelling
an in-flight refresh. Worker constraints are rebuilt when the setting changes.

Default WorkManager constraint is UNMETERED plus a runtime Wi-Fi check; unmetered
cellular is still forbidden. Foreground/live observer accepts validated metered
Wi-Fi, while a closed app waits for unmetered Wi-Fi. VPN/ambiguous transport is
rejected by WifiOnly; explicit AnyNetwork permits validated other transports.
These limitations are deliberate and visible here rather than equating
unmetered with Wi-Fi.

Payload connections are bound to an approved Android Network using
[Network.openConnection](https://developer.android.com/reference/android/net/Network#openConnection(java.net.URL)).
The client checks policy and network identity before each chunk and 64 KiB write.
The connection cannot silently fall back to cellular when the default network
changes. Paused queue items resume from the next server-confirmed session offset.
AndroidX's [picker default](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.PickMultipleVisualMedia)
uses the supported platform selection maximum rather than an arbitrary 50.

## Bounded diagnostics

Upload records include timestamp, stable queue id (SHA fallback for direct API
calls), sanitized filename, size, session id, starting offset, attempted chunk
bytes, confirmed offset, queue retry:internal retry number, pinned transport,
metered status and failure code. Attempted writes are counted before writing,
so failed writes are upper bounds, not measurements of radio/TCP overhead.
Confirmed offsets are server application commits, not bytes delivered to a socket.

Logs rotate by hour and at about 512 KiB, retain at most 24 files (~12 MiB), cap
individual messages, and cannot fail an upload. They contain no credentials,
Authorization values or source URI grants. Sum attempted chunk records and compare
offset intervals to investigate retransmissions; lifetime aggregate byte totals
are not persisted, and rotated-away history cannot be reconstructed.

## Regression coverage

- BatchEnqueueTest: full-queue-before-upload, duplicate URIs, worker mutex barrier,
  off-main staging, 500 sequential bounded streams, atomic rollback, process death
  before and after queue commit.
- SyncNetworkPolicyTest: fresh/upgrade defaults, cellular staging, foreground/
  retry/restored/Google restrictions, persisted opt-in/out, worker constraints,
  observer Wi-Fi resume, cellular callback exclusion, metered/unvalidated cases,
  acquired-lease handover and policy pause without failure/backoff.
- ResumableUploadProtocolTest: mid-chunk handover, server-confirmed resume,
  interrupted chunk and lost completion deduplication, exact ACK validation,
  transport/retry diagnostics and bounded rotation, large streamed media.
- LegacyRetryPathTest: Pending/Failed/Uploading adoption, duplicate ownership,
  backoff across restart, unreadable terminal legacy state, accurate pending counts,
  and removal of an owned item without resurrection through legacy adoption.
- Existing queue, cleanup policy, source cleanup, share parsing/ViewModel, Google,
  registration/429 storm, media streaming and UI tests remain enabled.
- Instrumentation adds platform picker maximum, explicit mobile switch and
  distinct Preparing/Waiting/Synchronizing/Failed/Completed UI labels.

## Validation and artifact

- Android unit tests: debug 74/74 and release 74/74 passed, zero failures/skips.
- Server Release integration tests: 56/56 passed.
- Android API 35 emulator instrumentation: 7 passed, 1 assumption-skipped,
  zero failures (runner reports OK, 8 tests). The optional DeviceNetworkSmokeTest
  requires a separately supplied live server. Automatic approval review rejected
  launching the isolated local HTTP server with "blocked by policy", without a
  more detailed reason, so that opt-in test was not run. In-process server
  integration tests and Android socket-based protocol tests did run successfully.
- lintDebug: zero errors / 63 warnings; lintRelease: zero errors / 62 warnings.
  Warnings include existing issues and intentional synchronous durability commits.
- assembleRelease, assembleDebug, assembleDebugAndroidTest: passed with the
  project Gradle wrapper, WSL JDK 17 and Android SDK 34.
- Existing Settings instrumentation assertion corrected to match an identifier
  substring within its localized label, rather than require full-string equality.
- No physical-phone radio handover, reboot or real Gallery interaction was
  performed; deterministic queue/network tests and emulator UI tests cover these
  code paths before the requested device test.
- Artifact: `android/app/build/outputs/apk/release/app-release.apk` (tracked),
  also `output/releases/PhotoSync-0.7.9-beta.apk` with adjacent SHA-256 file.
- APK size: 7,157,877 bytes.
- APK SHA-256: `2e88cd1ecbec5a5f315787858bfe839ec50b2bbd042126b3a9cde84e35af24af`.
- Package: `com.photosync.android`; versionCode `7009`; versionName `0.7.9-beta`.
- apksigner verify: passed, v2 signature, one signer.
- Signer certificate SHA-256:
  `0d3539a66b5938b4cabbd7d76acee286877a5e46648b4b8c6d4236f5c8b9862d`,
  identical to the checked previous 0.7.8 public release artifact/certificate.
- No signing keys/passwords are tracked. Diff whitespace and added-source secret
  checks passed. The pre-existing untracked `.agents/` content is excluded.
- The signed artifact is committed to GitHub; no production server, website or
  automatic-update manifest deployment is included in this task.
