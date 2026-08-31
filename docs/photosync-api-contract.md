---
tags:
  - photosync
  - api
  - contract
  - server
  - android
  - obsidian
status: approved-draft
updated: 2026-06-04
---

# PhotoSync API Contract

> Historical payload contract. Since 0.2.0-beta, all data requests require
> device authentication; the [device access protocol](device-access.md) supersedes
> the no-auth assumptions below. Google accounts and
> [family sharing](family-sharing.md) remain planned, not implemented.

> The new [web portal](web-portal.md) uses a separate Identity cookie and role
> policies. `/api/admin/dashboard` no longer accepts device tokens. Native
> `/api/devices`, `/api/albums`, `/api/files` remain device-scoped and do not accept
> browser cookies. Public server capabilities remain protocol 2.

## Scope

This note defines the initial HTTP contract between:

- Android client
- local PhotoSync server

This contract targets MVP:

- LAN usage
- automatic device authentication (since 0.2.0-beta)
- one-way sync `Android -> Server`
- file permanence on server

---

## Transport Overview

## Discovery

Discovery is not part of HTTP API.

Use:

- `UDP broadcast` for server discovery

Server response should contain:

- `server_name`
- `server_version`
- `server_ip`
- `http_port`

## API Transport

Use:

- `HTTP/1.1` or `HTTP/2`
- JSON for metadata endpoints
- streaming or multipart upload for media files

Recommended content types:

- `application/json`
- `multipart/form-data`

---

## Shared Types

## DeviceInfo

```json
{
  "device_uuid": "2b5c4056-31fb-4cc1-91f0-2331e0f11abc",
  "device_name": "Samsung S24",
  "app_version": "0.1.0"
}
```

## AlbumInfo

```json
{
  "device_uuid": "2b5c4056-31fb-4cc1-91f0-2331e0f11abc",
  "album_name": "Family"
}
```

## FileMetadata

```json
{
  "device_uuid": "2b5c4056-31fb-4cc1-91f0-2331e0f11abc",
  "album_name": "Family",
  "original_name": "IMG_0001.jpg",
  "mime_type": "image/jpeg",
  "size_bytes": 3821192,
  "sha256": "a44c1d6f4fc6d6e7964fbe9d7d8f7fd7dc6f4f6227e2d5a4e9c2e8f7f8c8c9d1",
  "created_at": "2026-06-04T13:25:11Z",
  "width": 4032,
  "height": 3024,
  "duration_ms": null,
  "is_video": false
}
```

## ErrorResponse

```json
{
  "error": {
    "code": "FILE_WRITE_FAILED",
    "message": "Could not write file to storage",
    "retryable": true
  }
}
```

---

## Endpoints

## `GET /api/server/info`

Purpose:

- check server availability
- return basic server metadata

### Response `200 OK`

```json
{
  "server_name": "Home PhotoSync",
  "server_version": "0.1.0",
  "storage_root": "D:\\PhotoSync",
  "status": "ok",
  "features": {
    "chunk_upload": false,
    "preview_generation": true,
    "photo_compression_policy_push": false
  }
}
```

---

## `POST /api/devices/register`

Purpose:

- create or update device record
- refresh `last_seen`

### Request

```json
{
  "device_uuid": "2b5c4056-31fb-4cc1-91f0-2331e0f11abc",
  "device_name": "Samsung S24",
  "app_version": "0.1.0"
}
```

### Response `200 OK`

```json
{
  "device_id": 12,
  "registered": true,
  "last_seen_at": "2026-06-04T13:30:02Z"
}
```

---

## `GET /api/albums?device_uuid=<uuid>`

Purpose:

- list known server album records for a device

### Response `200 OK`

```json
{
  "albums": [
    {
      "album_id": 21,
      "name": "Family",
      "server_folder_path": "devices/Samsung_S24/Family"
    },
    {
      "album_id": 22,
      "name": "Travel",
      "server_folder_path": "devices/Samsung_S24/Travel"
    }
  ]
}
```

---

## `POST /api/albums`

Purpose:

- create album metadata record
- ensure server folder exists

### Request

```json
{
  "device_uuid": "2b5c4056-31fb-4cc1-91f0-2331e0f11abc",
  "album_name": "Family"
}
```

### Response `200 OK`

```json
{
  "album_id": 21,
  "created": true,
  "server_folder_path": "devices/Samsung_S24/Family"
}
```

---

## `POST /api/files/check`

Purpose:

- check whether file already exists
- skip duplicate transfer

### Request

```json
{
  "device_uuid": "2b5c4056-31fb-4cc1-91f0-2331e0f11abc",
  "album_name": "Family",
  "original_name": "IMG_0001.jpg",
  "size_bytes": 3821192,
  "sha256": "a44c1d6f4fc6d6e7964fbe9d7d8f7fd7dc6f4f6227e2d5a4e9c2e8f7f8c8c9d1"
}
```

### Response When File Exists

```json
{
  "exists": true,
  "server_file_id": 1051,
  "relative_path": "devices/Samsung_S24/Family/2026/06/IMG_0001.jpg"
}
```

### Response When File Does Not Exist

```json
{
  "exists": false
}
```

---

## `POST /api/files/upload`

Purpose:

- upload full file in one request

Recommended for:

- MVP
- photos
- moderate-size video

### Multipart Form Fields

- `device_uuid`
- `album_name`
- `original_name`
- `mime_type`
- `size_bytes`
- `sha256`
- `created_at`
- `width`
- `height`
- `duration_ms`
- `is_video`
- `file`

### Response `201 Created`

```json
{
  "server_file_id": 1051,
  "stored_name": "IMG_0001.jpg",
  "relative_path": "devices/Samsung_S24/Family/2026/06/IMG_0001.jpg",
  "has_preview": true,
  "uploaded_at": "2026-06-04T13:31:47Z"
}
```

### Notes

- server must stream file directly to disk
- server must not load the whole file into memory
- hash should be validated against uploaded content

---

## `POST /api/files/upload-chunk`

Purpose:

- future support for large file chunking

Status:

- not required in first MVP

### Request

```json
{
  "upload_id": "up_3f9c17d1",
  "chunk_index": 0,
  "chunk_count": 8,
  "chunk_sha256": "2ed17f4c...",
  "device_uuid": "2b5c4056-31fb-4cc1-91f0-2331e0f11abc",
  "album_name": "Family",
  "original_name": "VID_0101.mp4"
}
```

Binary payload:

- raw chunk body

### Response

```json
{
  "upload_id": "up_3f9c17d1",
  "chunk_index": 0,
  "accepted": true
}
```

---

## `POST /api/files/upload-complete`

Purpose:

- finalize chunk upload
- assemble file
- verify final hash

### Request

```json
{
  "upload_id": "up_3f9c17d1",
  "device_uuid": "2b5c4056-31fb-4cc1-91f0-2331e0f11abc",
  "album_name": "Family",
  "original_name": "VID_0101.mp4",
  "sha256": "41cd8f7a..."
}
```

### Response

```json
{
  "server_file_id": 2051,
  "stored_name": "VID_0101.mp4",
  "relative_path": "devices/Samsung_S24/Family/2026/06/VID_0101.mp4",
  "uploaded_at": "2026-06-04T13:38:10Z"
}
```

---

## `GET /api/files/{id}/preview`

Purpose:

- fetch preview thumbnail

### Response

- binary image content
- content type: `image/jpeg` or `image/webp`

---

## `GET /api/devices`

Purpose:

- list known devices for server UI

### Response

```json
{
  "devices": [
    {
      "id": 12,
      "device_uuid": "2b5c4056-31fb-4cc1-91f0-2331e0f11abc",
      "device_name": "Samsung S24",
      "last_seen_at": "2026-06-04T13:31:47Z",
      "last_ip": "192.168.1.44",
      "files_uploaded": 1312,
      "bytes_uploaded": 5341712109
    }
  ]
}
```

---

## `GET /api/devices/{id}/files`

Purpose:

- list uploaded files for a single device

### Response

```json
{
  "files": [
    {
      "server_file_id": 1051,
      "album_name": "Family",
      "original_name": "IMG_0001.jpg",
      "relative_path": "devices/Samsung_S24/Family/2026/06/IMG_0001.jpg",
      "mime_type": "image/jpeg",
      "size_bytes": 3821192,
      "uploaded_at": "2026-06-04T13:31:47Z",
      "has_preview": true
    }
  ]
}
```

---

## `GET /api/stats/summary`

Purpose:

- populate local web dashboard

### Response

```json
{
  "device_count": 2,
  "file_count": 19450,
  "photo_count": 18790,
  "video_count": 660,
  "bytes_total": 84511790231,
  "failed_uploads_recent": 3,
  "last_upload_at": "2026-06-04T13:31:47Z"
}
```

---

## Status Codes

Recommended usage:

- `200 OK` for metadata success
- `201 Created` for successful upload creation
- `400 Bad Request` for invalid input
- `404 Not Found` for missing device/file references
- `409 Conflict` for duplicate finalize or inconsistent upload state
- `413 Payload Too Large` if server has configured limits
- `500 Internal Server Error` for unexpected failures

---

## Retry Guidance

Client should retry when:

- request timed out
- server unavailable
- `retryable = true`
- upload interrupted by network failure

Client should not automatically retry when:

- metadata is invalid
- album name is rejected
- file disappeared locally before upload begins

---

## Naming And Path Rules

Server must sanitize:

- device names
- album names
- file names

Sanitization rules:

- remove invalid filesystem characters
- trim trailing dots/spaces where needed
- keep user-visible readability

If file name collision occurs:

- keep original name when possible
- append timestamp suffix when needed

Example:

- `IMG_0001.jpg`
- `IMG_0001__20260604_153012.jpg`

---

## Open Questions

These are not blockers for documentation, but remain design questions:

- exact UDP discovery payload format
- whether upload should start with one-file-at-a-time or limited parallelism
- whether preview generation should happen on Android, server, or both
- whether hash should always be computed client-side before enqueue or lazily before upload
