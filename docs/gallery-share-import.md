# Gallery → PhotoSync share import

Implemented in Android `0.5.0-beta`.

## User flow

1. Open the normal Android gallery / Google Photos / Samsung Gallery.
2. Select one or many photos or videos.
3. Tap **Share** and choose **PhotoSync**.
4. PhotoSync opens a compact folder chooser showing the number of shared items.
5. Choose a writable PhotoSync folder and confirm the background upload.
6. PhotoSync prepares the complete shared batch and commits its durable queue. On an allowed network, it attempts sync while the chooser is still alive so Android can request source-deletion confirmation; otherwise it returns to Gallery with media pending.
7. WorkManager resumes pending media under the shared Wi-Fi-only default (mobile data requires opt-in) and updates completion/retry notifications.

Source originals are retained during staging and while waiting for Wi-Fi. Cleanup applies only after confirmed upload, using the folder/global policy captured at enqueue time. Android may require deletion confirmation.

## Android integration

`MainActivity` accepts both:

- `android.intent.action.SEND`
- `android.intent.action.SEND_MULTIPLE`

for:

- `image/*`
- `video/*`

URIs are read from `Intent.EXTRA_STREAM` and `ClipData` by a dedicated, short-lived `ShareReceiverActivity`. They are deduplicated and handed to the compact Compose chooser. The activity remains alive until all selected media has been copied to app-private queue storage, so temporary URI read grants cannot expire before queueing finishes.

After durable queueing or cancellation, the receiver activity finishes and Android returns to the source gallery. The normal PhotoSync activity and navigation stack are not opened.

## Import screen

`ui/share/ShareImportScreen.kt` provides:

- explicit writable-folder selection for every share;
- selected-media count;
- durable queueing progress;
- cancellation before queueing.

`ShareImportViewModel` queues the batch through the existing offline-first repository. Immediate sync and persistent WorkManager retry share the same committed queue and transport policy; no new server endpoint is required.

## Storage result

The server receives files exactly as uploads initiated from inside PhotoSync. With the current Bacus deployment the media ultimately lands in the Windows-backed PhotoSync storage mounted into the container as `/storage`.

## Known follow-ups

- Real Android gallery albums backed by `MediaStore` are a separate feature. PhotoSync logical folders are not yet created as physical Android gallery albums.
- Uploads resume from the server-confirmed byte offset after a connection failure. The complete mobile-network/reboot matrix still needs physical-device validation.
- Notifications require Android notification permission on Android 13 and newer.
