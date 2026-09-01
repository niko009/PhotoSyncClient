---
tags:
  - photosync
  - architecture
  - android
  - server
  - obsidian
status: approved-draft
updated: 2026-06-04
---

# PhotoSync Architecture

## Summary

`PhotoSync` is a one-way media synchronization system:

- source of truth for upload: Android client
- permanent archive: home server
- sync direction: `Android -> Server`
- server files are never deleted automatically
- Android uses logical folders
- server stores files in normal disk folders visible to the computer user

Core product goal:

> Sync photos and videos from the phone to the home computer in a meaningful folder structure, with background upload, visible progress, and server retention even if the local file is later removed from Android.

---

## Product Decisions

## Confirmed Rules

- Android keeps only logical folders inside the app
- server stores files in real folders on disk
- local original may be removed after upload, but the server copy must remain
- after local removal, the app should keep a preview-only entry
- photo compression after upload is allowed only for photos
- video compression is out of scope for MVP
- no authentication in MVP inside the local network
- server must prioritize fast transfer in LAN
- server metadata database is required
- upload success means verified and committed on server, not just HTTP success
- manual server IP configuration is required as fallback to UDP discovery

## Explicit Non-Goals For MVP

- no two-way sync
- no automatic deletion from server
- no remote internet relay
- no cloud dependency
- no video recompression
- no server-side authorization flow in first version

---

## User Model

## Android User Experience

The Android app presents user-created logical folders similar to gallery albums.

Example:

- `Family`
- `Work`
- `Travel`

User flow:

1. User creates a logical folder in the app.
2. User selects photos and videos from device media storage.
3. Files are assigned to that logical folder.
4. Background sync uploads them to the server.
5. After upload, the configured post-sync action is applied.

## Post-Sync Actions

- `nothing`
- `delete_local`
- `compress_photo`

Default:

- `nothing`

If the original local file is deleted:

- the app keeps the media record
- the app keeps a preview thumbnail
- the UI shows that the file exists on the server but not locally

---

## Storage Design

## Android Storage Model

Android does not physically reorganize files into app-owned filesystem folders.

Instead:

- folders are logical entities stored in app database
- media is referenced through `MediaStore`
- app tracks sync state and local/server availability

This avoids unnecessary filesystem moves and Android storage complications.

## Server Storage Model

Server stores files in normal human-readable folders on disk.

Recommended structure:

```text
PhotoSync/
  Samsung_S24_Mihail_Bacus/
    Family/
      IMG_0001.jpg
      IMG_0002.jpg
    Work/
      VID_0101.mp4
  Pixel_8_Anna_Bacus/
    Travel/
      IMG_9001.jpg
  system/
    photosync.db
    logs/
```

Why this structure:

- groups media by phone model and verified Google display name
- preserves app logical folders
- uses `local` as the user segment before Google linking
- sanitizes every directory as one portable Windows-safe segment
- keeps computer-side browsing intuitive

If duplicate file names occur, server renames safely:

- `IMG_0001.jpg`
- `IMG_0001__20260604_153012.jpg`

---

## Sync Model

## Direction

Sync is strictly one-way:

- `Android -> Server`

Server is a permanent archive target.

## File Lifetime Rules

- successful upload creates a permanent server copy
- local original may remain, be deleted, or be compressed
- local deletion must not affect the server copy
- app must continue to display server-only items

## Duplicate Avoidance

Before upload, client sends file metadata and hash.

Server checks whether the file already exists.

If already present:

- upload is skipped
- file is marked as uploaded in client state

---

## Networking

## Local Discovery

For LAN discovery, use:

- `UDP broadcast` for server discovery
- fixed HTTP port for API and uploads

Discovery flow:

1. Android sends UDP broadcast request like `who-is-photosync-server`.
2. Server responds with:
   - server name
   - IP address
   - HTTP port
   - version
3. Android stores the selected server.

Fallback:

- manual IP:port entry

## Transport Choice

Recommended:

- `UDP` only for discovery
- `HTTP` for metadata and file transfer

Do not use raw sockets for media upload in MVP.

Reason:

- HTTP is simpler to implement and debug
- HTTP can still stream files efficiently
- LAN bottlenecks will usually be disk, Wi-Fi, and file processing, not HTTP overhead
- chunked upload can be added later for large videos

Architecture position:

- keep `HTTP` as the primary transport
- do not switch to custom raw socket transfer for MVP
- add resumable or chunked upload later if large videos prove problematic

## Performance Strategy

To keep transfer fast:

- stream files directly to disk on server
- avoid buffering full files in RAM
- precompute or stream-compute hashes
- allow limited parallel uploads
- use retry with backoff on failure

Recommended concurrency:

- photos: `2-3` parallel uploads
- videos: `1` at a time

## Upload Integrity Model

The upload pipeline must be idempotent and verification-based.

Recommended flow:

1. client computes `content_hash`
2. client calls `files/check`
3. server decides whether upload is needed
4. client uploads file to temporary location
5. server verifies size and hash
6. server atomically commits the final file
7. only then client marks item as uploaded

Important rule:

- `HTTP success` alone is not enough to mark a file as safely uploaded
- the server must confirm verified durable commit

## Identity Model

Two identifiers should be treated separately:

- `asset_id`: client-side identity for a phone media item
- `content_hash`: content identity for deduplication

Usage:

- deduplication must use `content_hash`
- UI history and local lifecycle should use `asset_id`
- edited content with a new hash is a new server object, even if the visible file name is unchanged

---

## Android Data Model

Android local database should use `Room`.

## Table `albums`

- `id`
- `name`
- `created_at`
- `sort_order`

## Table `media_items`

- `id`
- `album_id`
- `device_media_id`
- `display_name`
- `mime_type`
- `local_uri`
- `local_path_hint`
- `size_bytes`
- `width`
- `height`
- `duration_ms`
- `created_at`
- `modified_at`
- `sha256`
- `sync_state`
- `server_path`
- `server_file_id`
- `local_state`
- `post_sync_action`
- `preview_path`
- `error_message`
- `retry_count`
- `last_sync_at`

## Table `sync_queue`

- `id`
- `media_item_id`
- `priority`
- `status`
- `next_attempt_at`

## Android State Values

### `sync_state`

- `discovered`
- `pending`
- `hashing`
- `checking`
- `uploading`
- `uploaded_unverified`
- `verified`
- `failed`
- `paused`

### `local_state`

- `original_present`
- `compressed_present`
- `preview_only`
- `missing`

### `post_sync_action`

- `nothing`
- `delete_local`
- `compress_photo`

## UI Meaning

- `verified + original_present` -> file exists locally and on server
- `verified + preview_only` -> file exists only on server, preview kept locally
- `pending` -> waiting for sync
- `failed` -> sync error

User-facing wording should avoid internal terms where possible:

- `On this phone`
- `On server only`
- `Uploaded`
- `Needs attention`
- `Waiting for server`
- `Skipped as duplicate`

---

## Server Data Model

For MVP, server metadata should use `SQLite`.

## Table `devices`

- `id`
- `device_uuid`
- `device_name`
- `first_seen_at`
- `last_seen_at`
- `last_ip`
- `app_version`

## Table `albums`

- `id`
- `device_id`
- `name`
- `server_folder_path`
- `created_at`

## Table `files`

- `id`
- `device_id`
- `album_id`
- `original_name`
- `stored_name`
- `relative_path`
- `mime_type`
- `size_bytes`
- `sha256`
- `created_at_client`
- `uploaded_at`
- `width`
- `height`
- `duration_ms`
- `has_preview`
- `is_video`

## Table `sync_sessions`

- `id`
- `device_id`
- `started_at`
- `ended_at`
- `files_uploaded`
- `bytes_uploaded`
- `files_skipped`
- `files_failed`

## Table `events`

- `id`
- `device_id`
- `file_id`
- `type`
- `message`
- `created_at`

---

## API Design

Transport:

- HTTP JSON for metadata
- HTTP file upload for media

## Endpoints

### `GET /api/server/info`

Returns:

- server name
- version
- root storage path
- availability

### `POST /api/devices/register`

Purpose:

- register device
- update `last_seen`

Input:

- `device_uuid`
- `device_name`
- `app_version`

### `GET /api/albums`

Purpose:

- optionally list server-side album records

### `POST /api/albums`

Purpose:

- create or update logical folder metadata for a device

Input:

- `device_uuid`
- `album_name`

### `POST /api/files/check`

Purpose:

- check whether a file is already present

Input:

- `device_uuid`
- `album_name`
- `sha256`
- `size_bytes`
- `original_name`

Response:

- `exists`
- `server_file_id`
- `relative_path`

### `POST /api/files/upload`

Purpose:

- upload a full file

Input metadata:

- `device_uuid`
- `album_name`
- `sha256`
- `original_name`
- `mime_type`
- `created_at`

Implementation note:

- use streaming write to disk

### `POST /api/files/upload-chunk`

Purpose:

- future support for large-file chunk upload

### `POST /api/files/upload-complete`

Purpose:

- finalize chunked upload

### `GET /api/files/{id}/preview`

Purpose:

- return preview thumbnail

### `GET /api/stats/summary`

Purpose:

- provide dashboard statistics

### `GET /api/devices`

Purpose:

- list known devices

### `GET /api/devices/{id}/files`

Purpose:

- list files for a device

---

## Background Sync

Android background sync should use:

- `WorkManager`
- foreground notification during active upload

Notification should show:

- total files
- uploaded files count
- failed files count
- current file name or progress

Why:

- Android is more likely to keep the job alive
- user sees live sync progress
- retries can be scheduled safely

Recommended Android support screens:

- `Home`
- `Folders`
- `Folder Detail`
- `Sync Queue`
- `Cleanup`
- `Settings`
- `Item Detail`

---

## Server UI Requirements

The server should expose a local web interface for visibility and monitoring.

## Required Screens

- devices
- albums
- recent activity
- file list
- statistics
- settings

## Required Server Visibility

- which devices connected
- when each device was last seen
- how many files each device uploaded
- how much storage is used
- where files are stored on disk
- recent errors
- current sync activity

Recommended server UI sections:

- `Dashboard`
- `Devices`
- `Albums`
- `Files`
- `Recent Uploads`
- `Failed Items`
- `Activity Log`

---

## MVP Scope

## MVP Includes

1. Android logical folders
2. media selection from `MediaStore`
3. local metadata in `Room`
4. UDP discovery
5. manual IP fallback
6. server metadata database
7. HTTP file existence check by `content_hash`
8. verified HTTP upload with temp write and final commit
9. server file storage in readable folders
10. foreground sync notification
11. `Sync Queue` and `Retry Failed`
12. post-sync action:
    - `nothing`
    - `delete_local`
13. preview-only state after local deletion
14. basic local web UI for server statistics

## MVP Excludes

1. internet relay
2. full auth system
3. server deletion
4. video recompression
5. full two-way reconciliation
6. advanced chunk upload in first pass

---

## Implementation Order

## Phase 1. Server Core

- build HTTP server
- configure root storage folder
- add SQLite
- implement:
  - `server/info`
  - `devices/register`
  - `albums`
  - `files/check`
  - `files/upload`
- write files to disk in readable directory structure
- record upload statistics

## Phase 2. Android Core

- create logical folder UI
- add media selection
- store metadata in `Room`
- render item cards with preview and sync state

## Phase 3. Discovery

- implement UDP broadcast server discovery
- add manual IP fallback
- persist selected server target

## Phase 4. Background Sync

- add `WorkManager`
- implement queue processing
- hash files
- call `files/check`
- upload missing files to temp location
- wait for server verification and commit confirmation
- retry failures
- show foreground notification

## Phase 5. Post-Sync Actions

- keep original
- delete original and convert item to preview-only
- compress photo after successful upload

## Phase 6. Server Web UI

- devices list
- album list
- recent uploads
- errors
- storage summary

## Phase 7. Optimization

- limited parallel uploads
- preview generation improvements
- chunked upload for large video
- Wi-Fi only option
- charging only option

---

## Review Outcomes

This section captures external critique and the current architectural response.

## Accepted Critiques

- a plain `HTTP 200` is not enough; upload must end with verified commit
- server metadata DB is mandatory and cannot be replaced by disk folders alone
- manual server entry must exist because UDP discovery is not universally reliable
- deduplication must be based on `content_hash`, not file name or timestamps
- preview-only is safe only after verified upload completion
- Android must be modeled around `MediaStore` and persistent local metadata, not filesystem assumptions
- server folder naming must be sanitized and collision-safe
- server must protect itself with temp-file writes, cleanup, and disk pressure awareness

## Deferred Critiques

These are valid, but not currently adopted into MVP scope:

- full pairing/auth flow
- TLS inside LAN
- full resumable chunk protocol from day one
- canonical object storage with a generated human-readable view instead of direct readable folders
- complex edited/original lineage handling for all media edge cases

## Held Position

These decisions remain unchanged after review:

- sync stays one-way `Android -> Server`
- server files are not auto-deleted
- Android keeps logical folders
- server keeps human-browsable real folders on disk
- raw sockets will not replace HTTP for primary media transfer in MVP

Rationale:

- these constraints keep the product understandable and implementation-bounded
- reliability comes from verification, indexing, and state handling, not from inventing a custom transfer protocol

---

## Architectural Principle

`PhotoSync` should be treated as a one-way home media archiver, not as a general cloud drive or bidirectional file sync engine.

That constraint keeps the product simpler, faster, and more reliable.
