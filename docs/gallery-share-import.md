# Gallery → PhotoSync share import

Implemented in Android `0.5.0-beta`.

## User flow

1. Open the normal Android gallery / Google Photos / Samsung Gallery.
2. Select one or many photos or videos.
3. Tap **Share** and choose **PhotoSync**.
4. PhotoSync opens a dedicated import screen showing the number of shared items.
5. Choose an existing PhotoSync folder, or create a new folder directly from the import screen.
6. PhotoSync uploads the shared media sequentially and shows batch progress.
7. When the batch is complete, the user returns to the normal PhotoSync UI.

The original media remains in the Android gallery. This flow does not move or delete the source files. Existing per-folder cleanup rules still apply after a successful upload because the normal `PhotoSyncRepository.uploadToFolder` pipeline is reused.

## Android integration

`MainActivity` accepts both:

- `android.intent.action.SEND`
- `android.intent.action.SEND_MULTIPLE`

for:

- `image/*`
- `video/*`

URIs are read from `Intent.EXTRA_STREAM` and `ClipData`, deduplicated, and handed to the Compose navigation layer. The activity keeps the original share intent until the import is finished or cancelled so the temporary URI read grants remain valid during the import screen.

After finishing or cancelling, the share payload is cleared and the activity intent is replaced with `ACTION_MAIN` to prevent the same batch from being imported again after a configuration change.

## Import screen

`ui/share/ShareImportScreen.kt` provides:

- existing PhotoSync folder selection;
- create-folder-and-upload in one step;
- selected-media count;
- sequential upload progress;
- cancel before upload;
- completion action.

`ShareImportViewModel` uses the existing `PhotoSyncRepository` and server API; no new server endpoint is required.

## Storage result

The server receives files exactly as uploads initiated from inside PhotoSync. With the current Bacus deployment the media ultimately lands in the Windows-backed PhotoSync storage mounted into the container as `/storage`.

## Known follow-ups

- Real Android gallery albums backed by `MediaStore` are a separate feature. PhotoSync logical folders are not yet created as physical Android gallery albums.
- Background/resumable batch transfer through WorkManager can be added later for very large imports or process death during upload.
- Per-file success/failure reporting can be made richer; current repository status remains the source of truth for failed items.
