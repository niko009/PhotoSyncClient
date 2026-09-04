# PhotoSync

PhotoSync is a self-hosted, one-way photo and video synchronization system for Android and a home server.

The Android application organizes media into logical folders and uploads it over the local network. The ASP.NET Core server stores the original files in ordinary folders and keeps metadata in SQLite. A successful upload is verified and committed on the server before the client treats the item as synchronized.

> **Status:** 0.6.5-beta under active development. This repository is the canonical source for both the Android client and the server.

Version 0.6.5-beta is the current update-gated Android build (`versionCode 11`). It must be distributed together with the matching server from this repository.

Version 0.6.1-beta fixes empty-album synchronization state and hardens Windows-backed storage. An empty album is now considered synchronized only after the server confirms it, album creation is rolled back if the physical storage directory cannot be created, and server startup reconciles missing directories for existing active albums. This prevents SQLite-only “ghost albums” when `/mnt/server` is unavailable or not writable.

Version 0.6.0-beta adds owner-facing folder access controls. Open one of your own albums and choose whether it stays private, is shared with the whole family, or is shared only with selected active family members. Per-folder permissions support `View` and `Contribute`; existing ACL values are loaded from the server before editing so reopening the dialog does not silently replace an existing selection.

Version 0.5.0-beta added direct Gallery → PhotoSync import: select one or many photos/videos in the normal Android gallery, choose **Share → PhotoSync**, then upload them to an existing PhotoSync folder or create a new folder without leaving the import flow. The source files remain in the phone gallery.

Version 0.4.0-beta added family sharing with separate Google accounts, secure email-bound invitation links, per-folder permissions, privacy-safe shared-folder discovery, and immutable archive semantics. Committed original photos and videos are never physically deleted by normal PhotoSync UI/API/jobs; archive actions are logical only.

Each family member signs in with their own Google account. Google proves identity while PhotoSync owns family membership and authorization. Invitations are one-time, expiring, revocable and bound to the exact verified Google email. This release does not require an SMTP/email server: the owner creates an invite link and shares it through the Android Share Sheet, Copy Link, or QR code.

Update **both** server and Android together for 0.6.x because folder sharing editing adds an owner-only read endpoint for the persisted ACL state and 0.6.1 adds the corrected empty-album synchronization model.

## Repository layout

| Path | Purpose |
| --- | --- |
| `android/app` | Kotlin and Jetpack Compose Android client |
| `server/PhotoSync.Server` | ASP.NET Core server and SQLite persistence |
| `server/tests/PhotoSync.Server.Tests` | Active server integration and unit tests |
| `docs` | Architecture, API contract, UX notes, and MVP roadmap |
| `design` | Product flows and design handoff material |
| `scripts` | Local development and device automation helpers |

## Documentation

- [Architecture](docs/photosync-architecture.md)
- [API contract](docs/photosync-api-contract.md)
- [MVP roadmap](docs/photosync-mvp-roadmap.md)
- [UX notes](docs/photosync-ux-notes.md)
- [Design overview](design/README.md)
- [Device authentication and migration](docs/device-access.md)
- [Web portal roles, operator setup and next steps (RU)](docs/web-portal.md)
- [Bacus Lab container deployment](docs/bacus-deployment.md)
- [Windows / VirtualBox storage and Google sign-in (RU)](docs/windows-virtualbox-setup.md)
- [Family accounts and folder permissions](docs/family-sharing.md)
- [Folder sharing controls](docs/folder-sharing-controls.md)
- [Gallery → PhotoSync share import](docs/gallery-share-import.md)

## Run the server

Requirements: .NET SDK 10.0.204 or a compatible .NET 10 SDK.

```powershell
dotnet restore server/PhotoSync.Server.slnx
dotnet run --project server/PhotoSync.Server/PhotoSync.Server.csproj
```

By default, runtime files and the SQLite database are written below `data/`. This directory is intentionally excluded from Git.

## Build the Android application

Requirements: JDK 17 (build tested with Corretto 17), Android SDK 34.

```powershell
./gradlew.bat :android:app:assembleDebug
```

A public update APK must be signed with the same permanent PhotoSync release certificate as previous public releases. The signing material is intentionally kept outside Git.

Configure the server address in the application settings when running on a physical device. The compile-time default is `https://photosync.bacus.dev`.

## Tests

```powershell
dotnet test server/PhotoSync.Server.slnx
./gradlew.bat :android:app:testDebugUnitTest
```

## Sync and privacy guarantees

- Android is the upload source of truth.
- Synchronization is one-way: Android to server.
- Committed server originals are never physically deleted by normal product flows.
- Archive/removal actions only hide or revoke metadata/access; they do not remove original media from disk.
- Upload completion means the server has verified and committed the file.
- Data endpoints require a per-installation secret plus device ID. ID alone is not a password.
- Google sign-in links identity; PhotoSync authorization controls family and folder access.
- Family membership alone does not expose another member's private folders.
- Folder sharing supports Private, WholeFamily, and SelectedPeople with View/Contribute/Owner semantics.
- Enrollment accepts new devices automatically up to the configured cap (5 by default). Initial storage and request limits are implemented; internet hosting still requires TLS, operator credentials, trusted proxy setup and operational checks.
