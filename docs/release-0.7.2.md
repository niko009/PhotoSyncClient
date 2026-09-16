# PhotoSync 0.7.2-beta

## Changes

- Gallery share import can create and immediately select a new album without
  opening the main PhotoSync navigation.
- A fresh device can connect its Google account while uploads are queued. The
  server then attributes existing albums and files to that verified account and
  background upload continues.
- The hosted server reads older originals from the persistent legacy volume
  while keeping all new uploads on the Windows-backed storage mount.

## Verification

- Android share-import and offline-account regression tests cover the new flows.
- Server storage-boundary and legacy-root tests pass.
- The release APK must retain the permanent Bacus Lab certificate before
  publication.
