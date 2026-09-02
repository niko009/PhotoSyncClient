# PhotoSync in-app updates and retry queue

Updated: 2026-09-02

This document describes two reliability features introduced for the upcoming Android `0.6.1-beta` release.

## 1. Automatic retry after temporary server/storage failure

### Problem

Before `0.6.1-beta`, a folder or photo could remain in a local pending/failed state after the PhotoSync server or `/mnt/server` storage became unavailable. Restoring the server did not guarantee that the Android client would retry the old item automatically.

### Folder recovery

`NetworkPhotoSyncRepository.refreshInternal()` now compares locally retained folders with the server album list. A local folder that does not yet exist remotely is retried with `createAlbum()` during refresh. This repairs folders created while storage was offline.

### Photo recovery

`RetryingPhotoSyncRepository` wraps the normal network repository. After a successful refresh it scans owned folders for local media in these states:

- `Pending`
- `Failed`
- stale `Uploading`

If the item still has a readable local URI, PhotoSync retries the upload. The server already deduplicates uploads by SHA-256 inside an album, so retrying an upload after an uncertain network response does not create a second server original.

After a confirmed successful retry, PhotoSync removes only the stale local queue item. Normal app flows do not physically delete the committed server original.

### Durable media permissions

Direct media selection now uses Android `OpenMultipleDocuments` instead of `GetMultipleContents`. PhotoSync calls `takePersistableUriPermission()` for selected media when the provider allows it. This lets queued uploads retain access across app restarts and makes later retry possible.

Media received through Android Share continues to follow the existing share-import flow.

## 2. In-app Android updates

### Goal

Users should not need to open bacus.dev manually, locate the APK, download it, and then start installation.

Starting with `0.6.1-beta`, PhotoSync checks the Bacus release manifest on app startup:

`https://bacus.dev/downloads/photosync/latest.json`

Example shape:

```json
{
  "versionCode": 7,
  "versionName": "0.6.1-beta",
  "apkUrl": "https://bacus.dev/downloads/photosync/photosync-android-0.6.1-beta.apk",
  "sha256": "...",
  "sizeBytes": 6800000
}
```

The client compares `versionCode` with `BuildConfig.VERSION_CODE`. If a newer release exists, it shows an in-app update dialog.

### Update flow

1. PhotoSync detects a newer version.
2. User chooses **Update**.
3. Android `DownloadManager` downloads the release APK.
4. PhotoSync computes SHA-256 for the downloaded APK.
5. The APK is accepted only when the checksum exactly matches `latest.json`.
6. PhotoSync opens Android's package installer.
7. Android verifies that the APK is signed by the same application certificate before allowing an in-place update.

PhotoSync declares `android.permission.REQUEST_INSTALL_PACKAGES` so it can request installation of its downloaded update.

On Android 8+, the user may need to allow PhotoSync as an installation source once. Android can still require explicit user confirmation for an update. PhotoSync does not attempt to bypass Android package-install security.

## 3. Release manifest ownership

`bacus.dev/scripts/generate-photosync-latest.mjs` generates `public/downloads/photosync/latest.json` from the canonical PhotoSync metadata in `src/data/projects.json` and the published APK file.

The bacus.dev build runs this generator before `astro build`, so publishing a new PhotoSync APK and updating the project metadata automatically updates the in-app update channel as well.

The generator is deterministic: it does not write timestamps or other values that change on every build.

## 4. Signing invariant

In-app updates only work as true Android updates when every published release is signed with the same permanent Bacus Lab release certificate.

The release pipeline must fail closed if the expected signing configuration, Java/Android SDK, `apksigner`, or permanent keystore is unavailable. A debug-signed or unknown-signed APK must never replace the public release artifact.

`0.6.1-beta` is not considered published until the release APK has been built with the permanent key, certificate-checked against the previous public release, copied to bacus.dev, and the public download metadata has been updated.

## 5. Storage prerequisite

Photo upload retry depends on a healthy server. The hosted server should report all of the following before retry testing:

- `/mnt/server` is mounted as `vboxsf`;
- the PhotoSync service can write to storage;
- `https://photosync.bacus.dev/health` returns HTTP 200.

The Bacus `verify-storage` job checks these conditions.
