# PhotoSync 0.6.7-beta

Android release code: `6007`.

## Fixed

- The add-photo action now uses Android Photo Picker instead of the document/file browser.
- Durable offline queue records keep the source URI, staged upload URI, original display name, and MIME type separately.
- Temporary staged files are removed after successful synchronization.
- `Keep` preserves the selected original.
- `Compress` creates a smaller local JPEG and requests removal of the original only after upload succeeds.
- `Delete` requests removal of the selected original only after upload succeeds.
- Android 10+ deletion uses the operating system's mandatory user-confirmation flow instead of silently swallowing storage-security failures.
- Pending deletion confirmations survive an app restart.
- Videos are preserved when `Compress` is selected because lossy video transcoding is not implemented.

Android may keep a cloud-only or protected picker item when its provider cannot supply a deletable local MediaStore item. PhotoSync reports that case and never treats a failed deletion as a failed server upload.
