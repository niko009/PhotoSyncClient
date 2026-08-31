# Bacus Lab deployment preparation

Status: registered and deployed through Bacus Agent on 2026-08-31.
Confirmed address: `https://photosync.bacus.dev`.

The latest user request permits initial storage in a local Ubuntu directory/volume.
Windows folder selection is no longer a blocker for the first test. The user
explicitly confirmed `photosync.bacus.dev` before registration and DNS changes.
See [web portal and operator setup](web-portal.md) for Identity roles, bootstrap
secrets, trusted proxy configuration, tested scope and remaining deployment gates.

Local checks: Compose configuration validation and server tests passed.
The image was built and started on Ubuntu by Bacus Agent; local Docker is unavailable.
The native SQLite dependency is now pinned to 2.1.13; the current NuGet audit
reports no known vulnerable dependencies.

## Security gate

The local 0.2.0-beta API now authenticates each installation with a secret and
isolates devices. The matching APK sends that secret; the old published APK
does not. See [device access](device-access.md). Enrollment is still automatic:
The first portal implementation adds login/enrollment rate limits, a 5-device cap,
25 MiB file cap, 10 GiB storage cap and a free-space reserve. Enrollment can be
closed through configuration; recovery and invitation-based enrollment remain planned.
Do not treat device isolation alone as approval to publish a public upload service.

The initial single-owner decision has been extended to a family instance with
separate Google accounts, invitations and per-folder permissions. Each participant
may have multiple devices. Private folders remain private by default; family
membership alone does not grant access to photos. See the
[family-sharing design](family-sharing.md). This is not a public multi-tenant hosting service.
Google and family access are not implemented yet. The default is private access
without an account. Full portal operation still requires private portal
credential/proxy provisioning; container and backup checks are tracked separately. Windows storage can
be configured later: [Windows / VirtualBox setup](windows-virtualbox-setup.md).

Secrets must be provisioned outside Git. The Bacus command bus only supports
registration and deployment; it is not a secret provisioning or shell interface.

## Container and data

The root Dockerfile builds only the .NET 10 server and runs as the non-root
runtime user on port 8080. The allowlisted build context excludes the Android
project, local databases, media, build outputs and development configuration.

`compose.yml` uses the existing `bacus-net` network with alias `bacus-photosync`.
It publishes no host ports. Network membership is not authentication: other
containers on that network can reach the API.

The deployed Compose configuration places both original media
and SQLite in the named volume `bacus-photosync-data`:

- `/data/system/photosync.db`: metadata database (including its WAL/SHM files).
- `/data/devices/`: uploaded media.
- `/data/_temp/`: temporary uploads.

This is not the final storage layout. The confirmed host is Windows with an Ubuntu
VirtualBox guest. The planned layout binds a VirtualBox shared Windows folder for
media and keeps SQLite in a local Ubuntu volume. The actual Windows path is still
undecided; Compose must be updated and mount availability checked before switching media storage.
Follow the linked setup plan; do not move SQLite onto the shared folder.

The initial empty volume inherits writable ownership from the image. An existing
or restored volume must be writable by the image's app user. Recreating the app
must retain this volume. Never remove it during redeploys. Do not run
`docker compose down --volumes` against this project.

No local photos or databases are included or migrated automatically. Arrange a
consistent backup of the entire data volume (stop uploads/the app during a file
copy, or use a SQLite-aware backup procedure), plus off-host backup and a restore
test. Persistent storage is not a backup. Check disk capacity before onboarding.

## Existing Bacus Agent contract

After the security gate is resolved and container testing passes:

1. Push the deployment-ready server to `niko009/PhotoSyncClient` on `main`.
2. Update PhotoSync in `niko009/bacus.dev/src/data/projects.json`: set
   `factory.managed` to `true`, repository to `niko009/PhotoSyncClient`, domain
   to `photosync.bacus.dev`, and visibility to the actual repository visibility.
   If `liveUrl` is supplied, it must equal `https://photosync.bacus.dev`.
3. Commit an update to `niko009/bacus-labs/agent/commands/trigger.json` on `main`
   with the exact commit message `bacus: register photosync`.
4. Bacus Agent registers the webhook, DNS/proxy route and project, then deploys
   via its privileged project helper. It recognizes this root `compose.yml`.
   Subsequent main-branch pushes use the normal GitHub webhook deployment flow.
5. Confirm unauthorized API calls fail, authorized Android sync succeeds, the
   downloaded file matches the uploaded hash, and data survives a redeployment.

Do not trigger registration merely to test connectivity: it creates a public
route. The public product page and APK remain separate from this server URL.

## Live verification — 2026-08-31

- Source release: `373c3da`; website manifest: `eff62e7`; registration command: `5930135`.
- Public DNS created; HTTPS `/health` returned 200 with protocol 2.
- Capabilities confirm device authentication, no Google/family support yet.
- A synthetic 68-byte PNG was uploaded and downloaded with matching SHA-256.
- Anonymous and incorrect-secret API requests returned 401.
- Probe device ID 1, file ID 1; this uses one of five device slots. No personal photos were used.
- Root redirects to `/portal/`; HTML, stylesheet and script are public, private APIs remain protected.
- Portal account/proxy provisioning is NOT complete: no default owner was created.
  Startup UI reports the pending setup instead of offering a nonfunctional login.
  Configure the private `portal.env` as described in web-portal.md.
- Local browser's network proxy initially could not resolve the new host. API tests
  used its public DNS address with the original hostname/SNI and TLS validation intact.
- Persistence verified after deployment `72a37ea`: the same device credential
  still authenticates and the downloaded file has the same SHA-256. Both the
  catalog and original survived container replacement.
- Updated portal HTML/CSS/JS return 200; `/api/portal/status` reports
  `loginAvailable: false` until operator setup. 29 local tests pass.
- Full authenticated browser UI and backup/restore remain unverified. Public DNS
  resolvers 1.1.1.1 and 8.8.8.8 both resolve the host; local negative DNS caching
  can temporarily prevent opening the new hostname.
