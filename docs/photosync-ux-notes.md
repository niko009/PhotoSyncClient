---
tags:
  - photosync
  - ux
  - android
  - server-ui
  - obsidian
status: reviewed-draft
updated: 2026-06-04
---

# PhotoSync UX Notes

## Purpose

This note captures UX additions and simplifications identified during product review.

Goal:

- reduce user confusion
- make sync state obvious
- make cleanup safe
- make server visibility operational, not decorative

---

## Core UX Principle

The user must always understand three things:

1. what is already safe on the server
2. what is still waiting or failing
3. what still exists on the phone versus only on the server

If those three things are not obvious, the product will feel unreliable even when upload technically works.

---

## Android Screens

## Home

Should show:

- current server status
- last successful sync
- last failed sync
- number of waiting items
- number of failed items
- quick actions:
  - `Sync now`
  - `Retry failed`

## Folders

Each folder should show:

- total item count
- not yet backed up count
- uploaded count
- on server only count
- failed count

## Folder Detail

Should support:

- media grid/list
- status badges
- filters
- bulk actions

Recommended filters:

- `All`
- `Waiting`
- `Uploading`
- `Uploaded`
- `Failed`
- `On server only`
- `Photos`
- `Videos`

## Sync Queue

This is required for clarity.

Sections:

- `Waiting`
- `Preparing`
- `Uploading`
- `Failed`
- `Recently uploaded`

Actions:

- `Retry failed`
- `Pause`
- `Resume`

## Cleanup

Purpose:

- help users safely free space

Should show:

- only items already verified on server
- estimated reclaimable storage
- cleanup options

Actions:

- `Remove local copies`
- `Compress uploaded photos`

## Item Detail

Should show:

- preview
- current status
- whether file exists on phone
- whether file exists on server
- server path
- upload history

Actions:

- `Retry upload`
- `Open preview`
- `Show server path`

Post-MVP:

- `Restore to phone`

## Settings

Should include:

- server address
- `Test connection`
- sync conditions
- default post-sync action
- folder-specific override support later

---

## User-Facing Status Language

Avoid overly technical labels in the main UI.

Prefer:

- `Waiting`
- `Preparing`
- `Uploading`
- `Uploaded`
- `On this phone`
- `On server only`
- `Needs attention`
- `Waiting for server`
- `Paused by rule`
- `Skipped as duplicate`

Avoid exposing internal labels like:

- `logical folder`
- `local_state`
- `preview_only`

Those can exist in docs and code, not in user-visible copy.

---

## Cleanup Safety Rules

Cleanup must be conservative.

Rules:

- never allow local removal for unverified items
- block cleanup for `pending`, `uploading`, and `failed`
- show exactly how many items are safe to clean up
- show estimated freed space before confirmation

Recommended confirmation copy pattern:

- `123 items are already backed up.`
- `3 items are not uploaded yet and will be kept on this phone.`

---

## Preview-Only UX

When a local original is removed:

- the app must not imply the original still exists locally
- a strong badge is required
- the item should remain browseable by preview

Required labels:

- `Stored on server`
- `Not stored locally`
- `Preview available`

This state must feel intentional, not like a broken item.

---

## Server UI Requirements

The server UI is not a gallery replacement.

Its job is:

- visibility
- health
- error inspection
- storage inspection

Required pages:

- `Dashboard`
- `Devices`
- `Albums`
- `Files`
- `Recent Uploads`
- `Failed Items`
- `Activity Log`
- `Settings`

Useful actions:

- `Open folder on disk`
- `Filter by device`
- `Filter by album`
- `Filter by date`
- `Filter by status`

---

## Onboarding

First-run setup should be simple.

Recommended steps:

1. find server or enter IP manually
2. test connection
3. choose sync behavior
4. create first folder and add media

Important onboarding message:

- `PhotoSync keeps a permanent copy on your home server.`
- `If you remove a file from the phone after backup, it will still appear here as a preview and remain on the server.`

---

## High-Value Post-MVP UX

- `Smart cleanup` suggestions
- reclaimable space estimates
- per-folder sync rules
- `Wi‑Fi only`
- `Charging only`
- `Only when battery > X%`
- `Restore to phone`
- duplicate explanation and skipped history

---

## Summary

The most important UX additions are:

- `Sync Queue`
- `Retry failed`
- explicit `On server only` state
- `Cleanup` screen
- strong separation between `temporary problem` and `user action required`

These features do more for trust than cosmetic UI polish.
