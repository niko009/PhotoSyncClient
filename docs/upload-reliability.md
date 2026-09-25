# Upload reliability (2026-09-25)

## Evidence and diagnosis

The production trace reports `IOException: Unexpected end of Stream` inside
`FormFeature.InnerReadFormAsync` at `FileEndpoints.UploadAsync` line 73. It
shows that the multipart body ended while ASP.NET was parsing it. It does not
identify which peer closed the connection. Device authentication and database
access had succeeded, and `/health` was 200. The request journal middleware
only reads JSON/text bodies; it skips multipart, so no second body read was
found in this path. The exact production close/timeout cause still needs
correlated Cloudflare Tunnel and Caddy logs.

The current Android source uses the resumable API, `PUT` chunks of 4 MiB with
fixed `Content-Length`, not multipart. A production POST to `/api/files/upload`
therefore came from a different/older APK or another client; verify the Redmi
app version before rollout. The resumable service had a separate confirmed
failure mode: bytes written before a dropped connection could exceed the
offset committed to SQLite, making every retry fail with an offset mismatch.

## Limits and recovery

`PhotoSync__MaxFileBytes` defaults to 2 GiB and is 2 GiB in `compose.yml`.
Kestrel allows that plus 2 MiB of multipart framing. `FormOptions` uses the
same ceiling. Storage verifies the actual size and SHA-256 before publishing
an original and adding its database row. Existing originals are never
overwritten or automatically removed. Multipart parsing uses ASP.NET's
disk-backed buffering; the Android resumable route streams chunks to a temp
file. Incomplete chunks now roll back to the committed offset.

Cloudflare's plan-dependent request limit can be below 2 GiB (100 MB on
Free/Pro). Android's 4 MiB chunks stay below that limit. Large legacy
single-request multipart uploads may not pass Cloudflare; update the APK to
the resumable client. Caddy and Tunnel configuration are managed outside this
repository, so their live body/timeout settings are unverified here. Before
deployment inspect those configurations and correlate request IDs/timestamps
with the app journal for interrupted uploads. Avoid buffering request bodies
in Caddy and ensure its per-request limit admits at least 4 MiB plus headers.

The Android client retries transient HTTP and I/O failures twice with 1 s and
2 s backoff; WorkManager retains unsuccessful items for later retry with its
exponential backoff. Starting an upload again obtains the committed offset.
The server reports `UPLOAD_INTERRUPTED` for readable broken requests and
`FILE_TOO_LARGE` for configured limit failures. On a disconnected client a
response cannot be delivered; the server logs the request ID, received bytes
and cancellation state.
