---
tags:
  - photosync
  - roadmap
  - mvp
  - planning
  - obsidian
status: approved-draft
updated: 2026-06-04
---

# PhotoSync MVP Roadmap

## Goal

Build a first working version of `PhotoSync` that:

- syncs photos and videos from Android to a home server
- preserves server copies permanently
- uses logical folders on Android
- stores files in real readable folders on the server
- supports background sync and visible progress

---

## MVP Definition

The MVP is successful when a user can:

1. install the Android app
2. create logical folders
3. add photos and videos from device media
4. discover or manually configure the server
5. sync files in the background
6. see upload progress and failures
7. confirm files exist on the server in readable disk folders
8. optionally remove local originals after verified upload
9. continue seeing preview-only items in the app

---

## Delivery Principles

- prioritize reliability over feature breadth
- prefer idempotent upload flow over naive raw speed
- keep storage human-readable on server
- preserve room for later pairing/auth without blocking MVP delivery
- treat server metadata database as required, not optional

---

## Milestones

## Milestone 1. Foundation And Project Skeleton

### Outcome

Both codebases exist with baseline structure:

- Android client project
- server project
- docs folder and architecture notes

### Tasks

- create Android app skeleton
- create server app skeleton
- choose server root storage path conventions
- add shared terminology to docs:
  - device
  - album
  - media item
  - preview-only
  - verified upload

### Exit Criteria

- both projects build
- docs define architecture and terminology

---

## Milestone 2. Server Core Storage

### Outcome

Server accepts file metadata and stores uploaded files in readable disk structure.

### Tasks

- add SQLite database
- implement `devices`, `albums`, `files`, `events`, `sync_sessions`
- implement disk storage root creation
- implement path sanitization
- implement safe file naming policy
- write files to temp location first
- verify hash and size
- atomically commit final file path

### Exit Criteria

- server can register a device
- server can create an album record
- server can store one uploaded file
- DB and disk state remain consistent after successful upload

---

## Milestone 3. Android Local Model

### Outcome

Android app can manage logical folders and local media records.

### Tasks

- create folder list screen
- create folder details screen
- integrate `MediaStore` picker
- add `Room` database
- store media metadata locally
- render grid/list items with preview and local sync status

### Exit Criteria

- user can create folders
- user can add media to a folder
- app persists state after restart

---

## Milestone 4. Server Discovery And Configuration

### Outcome

Android can locate the server automatically or manually.

### Tasks

- implement UDP discovery on server
- implement UDP scan on Android
- show discovered servers in UI
- add manual IP:port entry fallback
- save selected server target
- implement `GET /api/server/info`

### Exit Criteria

- user can connect using discovery
- user can connect using manual address
- app can validate server availability

---

## Milestone 5. Verified Upload Flow

### Outcome

Android can upload files reliably with verification.

### Tasks

- implement `POST /api/devices/register`
- implement `POST /api/albums`
- implement `POST /api/files/check`
- implement `POST /api/files/upload`
- compute client hash before upload
- skip duplicates by content hash
- mark file `uploaded` only after server verification and commit

### Exit Criteria

- same file is not re-uploaded unnecessarily
- failed upload does not create false success state
- server returns committed file metadata

---

## Milestone 6. Background Sync

### Outcome

Uploads work without the user keeping the app open.

### Tasks

- add sync queue table
- implement `WorkManager`
- add foreground notification during active upload
- add retry with backoff
- persist queue state across process death
- support batch sync

### Exit Criteria

- app continues syncing in background
- user sees progress notification
- failures are visible and retryable

---

## Milestone 7. Post-Sync File Policies

### Outcome

The app applies lifecycle rules after verified upload.

### Tasks

- implement `nothing`
- implement `delete_local` only after verified upload
- convert deleted local originals to `preview_only`
- implement `compress_photo`
- mark compressed photo as derivative local copy

### Exit Criteria

- local original is never deleted before server verification
- preview-only items remain visible in UI
- compressed local files are clearly marked as not-original

---

## Milestone 8. Server Web UI

### Outcome

Server provides a usable monitoring dashboard.

### Tasks

- show devices list
- show albums by device
- show recent uploads
- show failed uploads
- show total storage usage
- show server root path
- show last seen per device

### Exit Criteria

- a server user can understand what was uploaded, from where, and when

---

## Milestone 9. Hardening

### Outcome

The MVP becomes stable enough for real home use.

### Tasks

- add basic rate limiting
- add disk free space threshold protection
- add upload size validation
- add temp-file cleanup job
- add corruption/error logging
- add limited parallel photo upload
- keep videos sequential

### Exit Criteria

- large first sync does not easily overwhelm the server
- failures leave recoverable state

---

## Backlog After MVP

- resumable chunk upload for large video
- pairing code or shared secret
- Wi‑Fi only setting
- charging only setting
- RAW/JPEG pair awareness
- EXIF and metadata audit
- edited/original relationship handling
- preview regeneration
- restore local file from server
- search and filtering in server UI
- batch actions in Android UI

---

## Risks

## Technical Risks

- Android background restrictions interrupt long sync jobs
- MediaStore identifiers and local availability may change over time
- large videos may fail without resumable upload
- UDP discovery may fail on some home network setups
- readable server folder structure can drift from logical album semantics

## Product Risks

- users may misunderstand `preview_only` as full local ownership
- users may expect album moves to reorganize server folders automatically
- no pairing/auth may be acceptable only for tightly controlled home networks

---

## Recommended Development Order

1. server core storage and DB
2. Android local folder/media model
3. manual server configuration
4. verified single-file upload
5. UDP discovery
6. background sync queue and notification
7. delete-local and preview-only
8. server dashboard
9. performance and hardening

---

## Release Criteria For First Public Test

- at least one Android device can sync 100+ mixed media files to the server
- server folders remain readable by a normal computer user
- duplicate uploads are skipped
- interrupted sync can be retried safely
- preview-only mode works after verified upload
- server dashboard shows device and upload statistics
