# Folder sharing controls — PhotoSync 0.6.0-beta

Status: implemented on `main` on 2026-09-02.

## Goal

A family invitation only adds a verified Google user to the PhotoSync family. It never exposes existing albums automatically. Every album remains `Private` until its owner explicitly changes access.

PhotoSync 0.6.0-beta adds the missing owner UI for choosing which albums are shared and what invited family members may do in them.

## Android flow

Open one of your own PhotoSync albums. When the phone is linked to Google and the server can resolve that album as owned by the current user, the album header shows **Access / Доступ / Acces**.

The access dialog supports:

- `Private` — only the album owner can see the album.
- `WholeFamily` — all current and future active members of the same family receive the selected permission.
- `SelectedPeople` — only explicitly checked active family members receive access.

Permissions:

- `View` — preview/download only.
- `Contribute` — preview/download plus upload to the album.
- `Owner` is never grantable through this UI; only the album owner can change ACL/settings.

For `SelectedPeople`, each selected member has an independent `View` or `Contribute` permission.

Pending invitations are intentionally not selectable. A person must first accept the invite with the exact Google account bound to that invitation and become an active family member. After acceptance, reopening/refreshing the album access dialog makes that member available for selection.

## Server contract

Existing write endpoint:

```text
PUT /api/albums/{albumId}/sharing
```

New read endpoint added for safe editing:

```text
GET /api/albums/{albumId}/sharing
```

The read endpoint is owner-only and returns `404` to non-owners. It returns the persisted sharing mode, family permission and current selected-person ACL. This prevents the Android editor from opening with an empty draft and accidentally replacing an existing ACL.

Example response:

```json
{
  "album_id": 12,
  "mode": "SelectedPeople",
  "family_permission": "View",
  "selected_people": {
    "7": "View",
    "9": "Contribute"
  }
}
```

## Album resolution

The Android editor first resolves an album with the same name on the current device. This avoids accidentally editing a same-named album belonging to another linked phone. If the album exists only on another device owned by the same Google user, PhotoSync falls back to the owner-visible accessible-album list.

## Privacy guarantees

- New albums remain private by default.
- Family membership alone grants no photo access.
- ACL authorization is enforced server-side by `FolderAccessService`; Android UI is not the security boundary.
- Removed/inactive family members lose `WholeFamily` and `SelectedPeople` access immediately.
- Changing access never physically deletes or moves committed originals.
- Existing append-only archive policy remains unchanged.

## Verification

Server family-sharing tests now cover reading persisted `WholeFamily` and `SelectedPeople` settings and confirm that a non-owner cannot read the owner ACL endpoint.

Android build verification remains available through `.github/workflows/build-android.yml`; the 0.6.0-beta artifact name is `photosync-android-0.6.0-beta-debug-verification`.
