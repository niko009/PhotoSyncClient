# PhotoSync Android Screen Concepts

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

## Recommended Starting Direction

If choosing one direction to refine first:

- start from `concept-a-flow.svg`

Reason:

- clearest information hierarchy
- easiest to implement in Jetpack Compose
- good balance between gallery browsing and operational sync status
