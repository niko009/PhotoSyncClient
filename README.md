# PhotoSync

PhotoSync is a self-hosted, one-way photo and video synchronization system for Android and a home server.

The Android application organizes media into logical folders and uploads it over the local network. The ASP.NET Core server stores the original files in ordinary folders and keeps metadata in SQLite. A successful upload is verified and committed on the server before the client treats the item as synchronized.

> **Status:** MVP under active development. This repository is the canonical source for both the Android client and the server.

## Repository layout

| Path | Purpose |
| --- | --- |
| `android/app` | Kotlin and Jetpack Compose Android client |
| `server/PhotoSync.Server` | ASP.NET Core server and SQLite persistence |
| `server/PhotoSync.Server.Tests` | Server integration and unit tests |
| `docs` | Architecture, API contract, UX notes, and MVP roadmap |
| `design` | Product flows and design handoff material |
| `scripts` | Local development and device automation helpers |

## Documentation

- [Architecture](docs/photosync-architecture.md)
- [API contract](docs/photosync-api-contract.md)
- [MVP roadmap](docs/photosync-mvp-roadmap.md)
- [UX notes](docs/photosync-ux-notes.md)
- [Design overview](design/README.md)

## Run the server

Requirements: .NET SDK 10.0.204 or a compatible .NET 10 SDK.

```powershell
dotnet restore server/PhotoSync.Server.slnx
dotnet run --project server/PhotoSync.Server/PhotoSync.Server.csproj
```

By default, runtime files and the SQLite database are written below `data/`. This directory is intentionally excluded from Git.

## Build the Android application

Requirements: JDK 11 and Android SDK 34.

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
- The MVP is designed for a trusted local network and does not provide internet-facing authentication.
