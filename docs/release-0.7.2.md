# PhotoSync 0.7.3-beta

## Changes

- Gallery share import can create and immediately select a new album without
  opening the main PhotoSync navigation.
- A fresh device can connect its Google account while uploads are queued. The
  server then attributes existing albums and files to that verified account and
  background upload continues.
- The hosted server reads older originals from the persistent legacy volume
  while keeping all new uploads on the Windows-backed storage mount.
- The production upload limit is now 2 GiB (not 25 MiB). Kestrel, multipart
  parsing and storage verification share the same `PhotoSync__MaxFileBytes`
  setting; oversize uploads return structured HTTP 413 `FILE_TOO_LARGE`.
- Android now preserves an online server state for file-specific HTTP errors,
  so an oversize/corrupt/unauthorized file is not shown as a server outage.

## Verification

- Android share-import and offline-account regression tests cover the new flows.
- Server storage-boundary and legacy-root tests pass.
- The release APK must retain the permanent Bacus Lab certificate before
  publication.
