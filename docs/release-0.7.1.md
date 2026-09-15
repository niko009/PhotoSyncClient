# PhotoSync 0.7.1-beta

## Changes

- Settings now show the Android device name, server-scoped device UUID and the
  numeric server device ID for correlating a phone with server logs.
- Identifiers can be selected and copied; the device authentication secret is
  never displayed.
- The hosted deployment explicitly enables rate-limited enrollment for new
  phones, while retaining the configured device and storage limits.

## Verification

- Android unit tests and instrumentation-test APK compilation pass.
- Android lint passes.
- Server tests pass.
- The release APK must retain the permanent Bacus Lab certificate before
  publication.
