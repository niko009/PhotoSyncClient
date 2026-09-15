# PhotoSync 0.7.0-beta

Android release code: `7000`.

## Highlights

- A Google-signed-in device can browse albums and files uploaded by the same account's other devices.
- Cloud-only media is visibly marked and originals can be downloaded into the Android system gallery.
- Gallery sharing uses a dedicated folder chooser, persists the selected batch, returns to the gallery, and uploads through WorkManager.
- Background share uploads report queued, completed, and retry states through Android notifications.

## Verification

- Android unit tests, debug APK, instrumentation APK compilation, and lint pass.
- All 46 server tests pass, including cross-device file listing and download for the same Google account.
