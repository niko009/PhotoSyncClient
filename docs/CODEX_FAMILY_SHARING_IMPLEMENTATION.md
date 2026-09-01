# Codex task: Family Sharing + Immutable Media Archive

Implement the next PhotoSync phase in the current `main`. Before coding, read the repository and especially `README.md`, `docs/photosync-architecture.md`, `docs/photosync-api-contract.md`, `docs/device-access.md`, `docs/web-portal.md`, `docs/family-sharing.md`, and `docs/release-0.3.0.md`. Treat this document as the newest approved product decision when it conflicts with older planning docs. Update the docs first/as part of the work, then implement, test, and report the final commit SHA.

## 1. Non-negotiable invariant: committed originals are never physically deleted

PhotoSync is an append-only archive for successfully committed original photos/videos. Once the server has verified and committed an original file, normal PhotoSync UI/API/background jobs must NEVER physically delete it.

This applies when a user removes/hides a photo, deletes/archives a logical folder, removes a sync source, revokes sharing, leaves/signs out, unlinks a device, is removed from a family, deactivates an account, or when ACL/database relationships are removed.

Logical deletion/archive/tombstones are allowed and objects may disappear from normal UI/API results. Physical files must remain on storage. Do not recursively delete media directories. Do not introduce a garbage collector for committed originals. Any future physical cleanup must be a separate explicit administrator/offline maintenance operation outside normal PhotoSync user flows.

Preserve enough metadata to identify archived originals and avoid accidental loss/re-upload problems. Add this invariant prominently to architecture/docs and add tests proving it.

## 2. Family identity model

Each person uses their own Google account. Google proves identity; PhotoSync owns family membership and authorization. Permanent identity is the verified Google `sub`; email is a verified profile attribute and invitation constraint, not the primary identity key.

Use/adapt the existing models instead of creating duplicate identity architecture. Conceptually we need User, Family, FamilyMember, FamilyInvitation, Device ownership, Folder ownership/sharing, FolderPermission/ACL, Media uploader/folder relation, and optionally an access audit log.

For this phase a user belongs to at most one family. Existing users should safely migrate into their own family as Owner. Existing folders/media remain private by default. Do not move or rename physical media merely because family/ACL metadata changes.

Family Owner can manage membership but MUST NOT automatically gain access to another member's private folders.

## 3. Invitation flow: exact Gmail + manually shared secure link

We do NOT have/use an email server in this phase. Do not implement SMTP.

Flow:

1. Owner opens `Family -> Invite member`.
2. Owner enters the exact Google email expected to accept the invitation, e.g. `wife@gmail.com`.
3. Server creates a cryptographically strong, expiring, one-time invitation bound to that normalized expected email.
4. Store only a hash of the raw invite token. Never log/store the raw token in DB/audit logs.
5. Return a URL such as `https://<portal>/join/<token>`.
6. Android shows standard Share Sheet, Copy Link and QR Code. Do not hardcode only WhatsApp/Telegram; standard Android sharing should support them and other apps.
7. Recipient opens the URL. If the app is installed, use Android App Links where practical. Otherwise show a minimal web landing page with install/download guidance. Full deferred deep linking is not required if unsafe/overly complex; reopening the invite URL after install is acceptable.
8. Recipient signs in with Google.
9. Server verifies the Google identity/token and verified email. The authenticated verified email MUST equal the invitation's expected email. A request-body email is never proof of identity.
10. If the wrong Google account is used, reject acceptance and clearly tell the user which account (preferably masked where public) is expected.
11. Acceptance + membership creation must be atomic/idempotent and concurrency-safe. Accepted, expired or revoked invitations cannot be reused.
12. Owner can view pending invitations and revoke them.

Use reasonable expiration and rate limiting. Do not expose unnecessary family data on the unauthenticated landing page.

## 4. Family UI

Add a simple Android `Family` screen suitable for non-technical users: family name, current user/role, active members (minimal profile), pending invites for Owner, Invite action, revoke pending invite, and remove member.

Do not expose another member's devices, private folders, photo counts, storage usage or activity merely because they are in the same family.

Removing a member immediately revokes family access but NEVER physically deletes that person's originals or media they previously contributed.

## 5. Folder privacy and ACL

Every logical folder has an owner. New folders/sync destinations are PRIVATE by default.

Support these sharing choices:

- `Private / Only me`
- `Whole family`
- `Selected people`

For `Whole family`, use dynamic semantics: current and future active family members receive the configured family access level. Update older docs if they describe a different behavior.

Minimum permission levels:

- None
- View: list/view metadata, previews and download originals
- Contribute: View + upload new media
- Owner: Contribute + folder settings/ACL management

Contribute does not permit ACL changes or physical deletion. Even Folder Owner cannot physically delete committed originals.

Design the ACL so future Family Groups can be added later, but do NOT implement full custom groups in this phase unless trivial. Document groups as a later phase.

## 6. Logical removal semantics

If UI supports removing a photo/folder, make it clear that this removes/hides it from PhotoSync's active view but the archived original remains on server storage. Prefer wording such as `Remove from PhotoSync`, `Hide`, or an explicit confirmation that the original remains in the server archive.

Implement logical archive/delete fields/tombstones as appropriate for the existing schema. Never use logical deletion as a trigger to delete committed storage files.

## 7. Server authorization/security

Authorization MUST be server-side, not just UI filtering. Enforce membership/folder permission on every relevant endpoint: folder/media listing/details, search/counters, preview, original download, upload init/chunks/completion, hash/dedup checks, exports if any, and ACL operations.

Knowing/guessing a folder/media ID must not bypass authorization. Avoid leaking private object existence through dedup/hash checks, counters, search or caches. In particular, review the existing dedup/hash flow so a user cannot learn that an unauthorized person's private file/hash exists.

Check Contribute permission before upload starts AND again before final commit/publication. If permission was revoked mid-upload, do not publish the media into the folder. Preserve the existing verify+commit upload guarantee. If a valid original has already become a committed immutable archive object, do not delete it.

## 8. Migration and storage

Preserve all current data. Existing users/devices/folders/media must migrate safely. Existing folders/media stay private until explicitly shared. Use stable internal IDs in storage/database relationships, not email/display names/client-provided paths. ACL changes must not require physical media moves.

Create proper SQLite migration(s) consistent with the current project approach.

## 9. Tests and validation

Add/extend tests for at least:

- Owner creates invite; ordinary member cannot.
- Invalid email rejected.
- Invite token stored hashed only.
- Correct verified Google email accepts; wrong email rejected.
- Expired/revoked/already-used invite rejected.
- Concurrent/double acceptance does not create duplicate membership.
- Google `sub` is permanent identity.
- Private folder accessible only to owner.
- WholeFamily and SelectedPeople behavior.
- View cannot upload; Contribute can; Contribute cannot edit ACL.
- Removed/revoked member immediately loses access.
- ID guessing cannot bypass authorization.
- Private media/hash is not leaked by dedup.
- Permission is checked at upload start and completion.
- Existing migration leaves media private and physical files untouched.
- Logical photo removal keeps the physical file.
- Folder archive/removal keeps all physical files.
- Family member removal/revocation keeps all physical files.
- No normal cleanup/background path deletes committed originals.

Run all existing server tests and Android tests, server build/publish, Android debug build, and release build if it does not require unavailable secrets. Check regressions in existing sync and Google same-user multi-device behavior.

## 10. Documentation/version/final report

Update `docs/family-sharing.md` and architecture/API docs to reflect the implemented behavior, especially:

- exact expected Google-email invitation;
- manual invite-link sharing (no SMTP);
- verified Google identity and permanent `sub`;
- one-time expiring/revocable invites;
- private-by-default folders;
- WholeFamily + SelectedPeople ACL;
- immutable committed-original invariant;
- logical removal only;
- member removal never deletes originals;
- custom groups deferred.

Increment the project/app version according to the repository's established versioning rules.

Before finishing, inspect `git diff`, migrations and tests. Commit the completed coherent phase to the appropriate working branch/current workflow. In the final report provide: implementation summary, DB migrations, API endpoints, Android/web flows, security decisions, test/build results, new version, commit SHA, and anything intentionally deferred.

## Explicitly out of scope

Do not add SMTP/mail server, password login, public user search/directory, public photo links, guest access, Google Family API, physical deletion of committed originals, committed-media garbage collection, end-to-end encryption, family ownership transfer, multiple families per user, or full custom family groups in this phase.
