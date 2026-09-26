# PhotoSync 0.7.6-beta upload investigation

## Evidence and root cause status

The observed production request was `POST /api/files/upload`, stopped after
104,857,600 bytes with HTTP 499 and `Unexpected end of request content`.
That confirms a multipart request reached the legacy server endpoint and its
body ended early. It does **not** identify the Android process or APK that sent
it. The repository contains no client-side legacy multipart call in the
0.7.5-beta source. The APK served at `bacus.dev` on 2026-09-26 has SHA-256
`b50f7b7c83dbd85c9c4fe30660042be47b3ee93115f487edeb64b4b24ed8f486`,
matching the 0.7.5-beta build, and its DEX contains resumable routes with no
exact legacy upload route. No Redmi is connected through ADB in this workspace.
The actual sender of the multipart request remains unconfirmed; compare the
installed APK hash, package name, and server request device UUID or audit trace
before attributing it to the current app.

## Android runtime paths

Manual folder upload, Gallery Share, immediate offline-queue sync, WorkManager
retry, and retry of old `Pending`/`Failed`/`Uploading` local records all reach
`NetworkPhotoSyncRepository.uploadToFolderInternal`, then
`PhotoSyncApiClient.uploadFile` or `uploadFileToAlbum`. Both use start, 4 MiB
fixed-length `PUT` chunks, durable offset resume, and complete. There is no
multipart fallback. Old offline-queue JSON with only `local_uri` is read as
both source and staged URI; an old `upload_type` field is ignored. Such items
are retried through the current delegate and removed once after success.

0.7.6-beta also treats an upload offset conflict as retryable, rejects a
non-progressing or implausible chunk response, preserves API connection status
after file-specific failures, and stores a separate file failure code for the
UI. Queue matching now uses the media URI, so another file with the same name
is neither hidden nor removed. A Gradle `preBuild` guard fails if a production Android source file adds
the exact legacy endpoint. The server's legacy endpoint remains for old clients.

## Verification

- Android release unit tests: 40 passed. Protocol tests cover 26 MiB photos,
  101 MiB and 301 MiB videos, 4 MiB body limit through the configured 2 GiB
  maximum, chunk interruption and offset resume, completion response loss,
  old queue records, deduplication, and counters.
- Android `lintRelease`: 0 errors, 48 warnings.
- Signed `assembleRelease`: passed using the existing production certificate.
- Server tests: 56 passed.
- Exact legacy endpoint references in Android production source: 0.
- Exact legacy endpoint references in release DEX: 0; resumable route present.

## Built APK

- Version code: `7006`; version name: `0.7.6-beta`.
- Local artifact: `output/releases/photosync-android-0.7.6-beta.apk`.
- Size: 7,135,461 bytes.
- SHA-256: `2c2511fc0733b481cf1342d49aef435c7d83f389c3094c342037563d439cbf9a`.
- Signing certificate SHA-256: `0d3539a66b5938b4cabbd7d76acee286877a5e46648b4b8c6d4236f5c8b9862d`,
  unchanged from 0.7.5-beta.

No push or deployment is part of this investigation. Production attribution
requires the installed Redmi APK or its hash and the server trace's device ID.
