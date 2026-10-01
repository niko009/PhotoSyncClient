# PhotoSync 0.7.8-beta (7008)

## Register and refresh storm

Previously each upload registered the device, then ran a full library refresh
that registered it again and fetched summary, devices, accessible albums, albums
and each album's files. Registration POSTs exhausted the portal-login rate limit.
A registration 429 before the next upload appeared as a sync failure.

Registration is cached durably by server-derived installation UUID, device name
and app version. A process-wide lock prevents parallel duplicate registrations
across API clients. New origins and changed metadata require registration;
DEVICE_NOT_FOUND invalidates cached enrollment. Credentials and rate limits
are unchanged. Refresh checks cached enrollment without a registration POST.

Completion synchronously saves the local server-backed photo, without a full
refresh per file. Explicit/UI/WorkManager refresh remains available. Cleanup still
runs only after verified completion through the existing MediaStore flow. Cleanup
or later refresh failure cannot mark a committed upload Failed.

A durable marker identifies the staged queue copy. Recovery after a crash between
completion and queue removal does not reupload it, including when cleanup deleted
the original. A newly imported copy of the same source URI is not mistaken for
an earlier completed queue item. Queue removal precedes stale attempt cleanup
and notifications.

Transient queue failures persist exponential backoff (30 seconds up to 64 minutes)
and Retry-After. A 429 pauses the entire queue. Registration and refresh persist
separate cooldowns. HTTP-date and seconds Retry-After are supported. Long upload
cooldowns return to the durable queue rather than blocking a thread. Legacy queue
entries default to no delay and Keep cleanup, retaining existing migration.

## Regression coverage

A counting HTTP server verifies 12 consecutive completed uploads use one
registration and zero per-file refreshes. Refresh 429 preserves Synced photos and
an empty queue across restart. Other tests cover concurrent/restarted registration,
metadata changes, server-origin changes, persisted registration 429, legacy failed
records, a committed record whose original was deleted, and a real upload 429
stopping a 12-file batch across repeated sync and restart.

Existing protocol, large-media, resume, hash, deduplication, cleanup, folder override,
Google/share and server security tests remain in validation. No server code,
rate limits, storage policy or production data changes are part of this release.

## Validation

- Gradle check: passed; debug and release unit tests: 55 each, zero failures.
- Server Release tests: 56 passed.
- lintRelease: zero errors, 56 warnings; lintDebug also passed.
- assembleRelease and compileDebugAndroidTestKotlin: passed in existing WSL JDK 17/SDK 34.
- Instrumentation execution: unavailable, no ADB device connected.
- Windows Gradle stopped without diagnostics; the final source was validated in WSL.
- APK: output/releases/app-release.apk, 7,140,765 bytes.
- SHA-256: `5af631373e213c4aee3274a63908d01181155a11f3e67d2b330a7ef8f80da79e`.
- aapt: com.photosync.android, versionName 0.7.8-beta, versionCode 7008; no debuggable flag.
- apksigner verify: passed, APK Signature Scheme v2, one signer.
- Certificate SHA-256: `0d3539a66b5938b4cabbd7d76acee286877a5e46648b4b8c6d4236f5c8b9862d`, identical to published 0.7.7.
- Publication follows existing bacus.dev versioned APK, adjacent checksum and latest.json workflow.
