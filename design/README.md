# PhotoSync Android Screen Concepts

## Current direction: warm family album

The user selected a warm family-photo-album direction. See
[warm-family-v1](warm-family-v1/README.md) for the new screen concepts, proposed
visual system and implementation constraints. These are design mockups, not
screenshots of working family/authentication features. The earlier explorations
below remain as history and are not the selected direction.

For comparison, [modern-warm-v2](modern-warm-v2/README.md) explores the same core
screens with sans-serif typography and flatter surfaces. The user liked v1 and
requested this alternative; the final choice between the two is still pending.

This folder contains early visual concepts for the Android client.

Files:

- `concept-a-flow.svg` - calm utility-first layout with strong sync visibility
- `concept-b-flow.svg` - warmer gallery-first layout with larger media emphasis
- `concept-c-flow.svg` - denser operator-style layout focused on queue and status

Each concept includes four core screens:

- `Home`
- `Folder Detail`
- `Sync Queue`
- `Cleanup`

These are design explorations, not final assets.

## Design Intent

Common product goals across all concepts:

- make server state obvious
- make unsafe cleanup hard
- make `on phone` vs `on server only` easy to understand
- keep folder-based mental model simple

## Earlier recommendation (superseded by warm-family-v1)

If choosing one direction to refine first:

- start from `concept-a-flow.svg`

Reason:

- clearest information hierarchy
- easiest to implement in Jetpack Compose
- good balance between gallery browsing and operational sync status
