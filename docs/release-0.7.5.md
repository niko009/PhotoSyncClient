# PhotoSync 0.7.5-beta deployment checklist

## Built artifact

- Source version: `versionCode 7005`, `versionName 0.7.5-beta`.
- APK: `output/releases/photosync-android-0.7.5-beta.apk`.
- Size: 7,134,913 bytes.
- APK SHA-256: `b50f7b7c83dbd85c9c4fe30660042be47b3ee93115f487edeb64b4b24ed8f486`.
- Signing certificate SHA-256: `0d3539a66b5938b4cabbd7d76acee286877a5e46648b4b8c6d4236f5c8b9862d` (same as public 0.7.4-beta).
- `aapt dump badging` confirms package `com.photosync.android`, code 7005, name 0.7.5-beta.
- DEX inspection confirms `/api/files/uploads` and no exact `/api/files/upload` string.

The public `latest.json` and public APK were checked on 2026-09-25. Both are
0.7.4-beta/code 7004, with SHA-256
`ccd9725a23f1b39c6657c0d6803db36e8ea21b6445ae9ae141bc40dab9bbd283`.
The published DEX contains the legacy `/api/files/upload` endpoint and no
resumable endpoint. Source code had changed to resumable without raising
versionCode. The updater compares versionCode only, so a phone running the
published 7004 could not discover the unpublished resumable implementation.

## Before publishing

1. Deploy the updated server from this repository. Run `dotnet test
   server/PhotoSync.Server.slnx` and `docker compose -f compose.yml config
   --quiet` on the release source. Preserve the existing SQLite volume and
   media mount. Do not remove originals or change the storage root.
2. Inspect the **live** Caddy and Cloudflare Tunnel configuration. Confirm
   `/api/files/uploads` allows at least a 4 MiB request body, does not buffer
   whole media files, and has no short read/write/idle timeout that aborts a
   4 MiB chunk on a slow connection. This configuration is not present in this
   repository; do not change it without observing the active values. Confirm
   Cloudflare's zone Maximum Upload Size remains above 4 MiB.
3. Copy the signed APK to the current bacus.dev checkout at
   `public/downloads/photosync/photosync-android-0.7.5-beta.apk`. Update the
   PhotoSync `downloads.android` entry in `src/data/projects.json` with that
   URL, version `0.7.5-beta`, and the APK SHA-256 above. Run the site's build;
   `scripts/generate-photosync-latest.mjs` must produce `latest.json` with
   versionCode 7005. Compare it with
   `output/releases/photosync-0.7.5-beta-latest.json` before publishing.
4. Confirm the published APK SHA-256, byte count and signing certificate match
   the built artifact, and that the public `latest.json` points to it. Keep
   the previous public APK available; do not replace it in place.
5. On an installed 7004 phone, reopen PhotoSync, accept the update prompt and
   Android installer confirmation, then verify the installed version is 7005.
   Upload a photo and a video through the public hostname. Server logs must
   show `POST /api/files/uploads`, `PUT /api/files/uploads/{id}` and completion,
   without a legacy `POST /api/files/upload` from the updated client. Interrupt
   a chunk and confirm a later sync resumes without duplicate files/counts.

## Local verification

Windows Gradle failed before compilation because Java NIO's Windows Unix
domain socket connection returned `Invalid argument: connect`. A Linux JDK 17
and Android SDK 34 in WSL built an identical copy of the Android source.
`testDebugUnitTest` passed 32 tests, `lintRelease` reported 0 errors (48
warnings), and `assembleRelease` produced the signed APK. The release build
passed after adding the missing Romanian translations.
