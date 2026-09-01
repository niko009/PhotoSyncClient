# PhotoSync

PhotoSync is a self-hosted, one-way photo and video synchronization system for Android and a home server.

The Android application organizes media into logical folders and uploads it over the local network. The ASP.NET Core server stores the original files in ordinary folders and keeps metadata in SQLite. A successful upload is verified and committed on the server before the client treats the item as synchronized.

> **Status:** MVP under active development. This repository is the canonical source for both the Android client and the server.

Version 0.3.0-beta adds optional Google account linking to the warm album design.
Without Google, every installation still has its own private device space. After
sign-in, devices linked to the same verified Google account share one archive.
Update **both** server and Android together. See
[release notes and migration warnings](docs/release-0.3.0.md).

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
- [Family accounts and folder permissions (RU)](docs/family-sharing.md) (planned, not implemented)

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

Configure the server address in the application settings when running on a physical device. The compile-time default is intended only for local development.

## Tests

```powershell
dotnet test server/PhotoSync.Server.slnx
./gradlew.bat :android:app:testDebugUnitTest
```

## Sync guarantees

- Android is the upload source of truth.
- Synchronization is one-way: Android to server.
- Server files are never deleted automatically.
- Upload completion means the server has verified and committed the file.
- Data endpoints require a per-installation secret plus device ID. ID alone is not a password.
- Google sign-in and same-account device linking are implemented. Linking does not
  recover archives created by an older, already-lost device key; family sharing,
  background folder watching and operator-assisted recovery remain planned.
- Enrollment accepts new devices automatically up to the configured cap (5 by default). Initial storage and request limits are implemented; internet hosting still requires TLS, operator credentials, trusted proxy setup and operational checks. Invitation-based enrollment is planned.
