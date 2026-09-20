# PhotoSync 0.7.4-beta

Android release code: `7004`.

## Fixed

- The cleanup policy is captured when media enters the offline queue. A later
  setting change cannot delete originals from an earlier queue entry.
- Gallery share uploads synchronise while the share activity is active when a
  network is available, allowing Android to show its required deletion consent
  before returning to the gallery.
- Existing queue records created before this policy snapshot are treated as
  `Keep`, preventing retroactive source deletion.

## Update compatibility

The APK is signed with the permanent Bacus Lab release certificate and can
update existing release-signed PhotoSync installations in place.
