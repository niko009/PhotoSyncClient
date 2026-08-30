using Microsoft.EntityFrameworkCore;
using PhotoSync.Server.Contracts;
using PhotoSync.Server.Data;

namespace PhotoSync.Server.Endpoints;

public static class AdminEndpoints
{
    public static IEndpointRouteBuilder MapAdminEndpoints(this IEndpointRouteBuilder endpoints)
    {
        endpoints.MapGet("/admin", () => Results.Content(AdminPageHtml, "text/html; charset=utf-8"));

        endpoints.MapGet("/api/admin/dashboard", async (PhotoSyncDbContext dbContext, CancellationToken cancellationToken) =>
        {
            var devices = await dbContext.Devices.AsNoTracking().ToListAsync(cancellationToken);
            var albums = await dbContext.Albums.AsNoTracking().ToListAsync(cancellationToken);
            var files = await dbContext.Files.AsNoTracking().ToListAsync(cancellationToken);

            var deviceRows = devices
                .OrderByDescending(x => x.LastSeenAtUtc)
                .Select(device =>
                {
                    var deviceAlbums = albums.Where(album => album.DeviceId == device.Id).ToList();
                    var deviceAlbumIds = deviceAlbums.Select(album => album.Id).ToHashSet();
                    var deviceFiles = files.Where(file => deviceAlbumIds.Contains(file.AlbumId)).ToList();
                    var albumRows = deviceAlbums
                        .OrderBy(album => album.AlbumName)
                        .Select(album =>
                        {
                            var albumFiles = deviceFiles.Where(file => file.AlbumId == album.Id).ToList();
                            return new AdminAlbumItem(
                                album.Id,
                                album.AlbumName,
                                albumFiles.Count,
                                albumFiles.Sum(file => file.SizeBytes),
                                album.StorageFolderName);
                        })
                        .ToList();

                    return new AdminDeviceItem(
                        device.Id,
                        device.DeviceUuid,
                        device.DeviceName,
                        device.AppVersion,
                        device.LastSeenAtUtc,
                        deviceAlbums.Count,
                        deviceFiles.Count,
                        deviceFiles.Sum(file => file.SizeBytes),
                        albumRows);
                })
                .ToList();

            return Results.Ok(new AdminDashboardResponse(
                deviceRows.Count,
                albums.Count,
                files.Count,
                files.Sum(x => x.SizeBytes),
                deviceRows));
        });

        return endpoints;
    }

    private const string AdminPageHtml = """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>PhotoSync Admin</title>
  <style>
    :root {
      color-scheme: dark;
      --bg: #0b0d10;
      --panel: #12161c;
      --panel-2: #171c23;
      --text: #edf2f7;
      --muted: #8d98a7;
      --line: rgba(255,255,255,.08);
      --good: #5fd38c;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      font-family: Inter, ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
      background:
        radial-gradient(circle at top left, rgba(122,162,255,.12), transparent 32%),
        radial-gradient(circle at top right, rgba(95,211,140,.08), transparent 26%),
        var(--bg);
      color: var(--text);
    }
    .wrap { max-width: 1160px; margin: 0 auto; padding: 40px 20px 56px; }
    .hero { display: flex; justify-content: space-between; gap: 24px; align-items: end; margin-bottom: 28px; }
    h1 { margin: 0; font-size: clamp(32px, 4vw, 52px); letter-spacing: -0.04em; }
    .sub { color: var(--muted); max-width: 52ch; margin-top: 10px; line-height: 1.5; }
    .pill { display: inline-flex; align-items: center; gap: 8px; padding: 10px 14px; border: 1px solid var(--line); border-radius: 999px; color: var(--muted); background: rgba(255,255,255,.02); }
    .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--good); box-shadow: 0 0 0 4px rgba(95,211,140,.12); }
    .stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; margin: 24px 0 28px; }
    .card { background: linear-gradient(180deg, rgba(255,255,255,.03), transparent), var(--panel); border: 1px solid var(--line); border-radius: 22px; padding: 20px; }
    .label { color: var(--muted); font-size: 13px; text-transform: uppercase; letter-spacing: .12em; }
    .value { margin-top: 12px; font-size: 34px; font-weight: 700; letter-spacing: -0.04em; }
    .table { display: grid; gap: 12px; }
    .row { display: grid; grid-template-columns: 2fr 1fr 1fr 1fr 1fr; gap: 12px; align-items: center; }
    .head { color: var(--muted); font-size: 13px; text-transform: uppercase; letter-spacing: .12em; padding: 0 8px; }
    .device { background: var(--panel-2); border: 1px solid var(--line); border-radius: 18px; padding: 18px; }
    .name { font-size: 18px; font-weight: 650; }
    .meta { color: var(--muted); font-size: 13px; margin-top: 5px; overflow-wrap: anywhere; }
    .num { font-variant-numeric: tabular-nums; text-align: right; }
    .tree { margin-top: 14px; display: grid; gap: 8px; }
    .folder { display: flex; justify-content: space-between; gap: 12px; padding: 12px 14px; border-radius: 14px; background: rgba(255,255,255,.03); border: 1px solid var(--line); }
    .folder-name { font-size: 14px; font-weight: 600; }
    .folder-path { color: var(--muted); font-size: 12px; margin-top: 4px; overflow-wrap: anywhere; }
    .folder-stats { color: var(--muted); font-size: 13px; white-space: nowrap; }
    @media (max-width: 900px) {
      .stats, .row { grid-template-columns: 1fr 1fr; }
      .row.head { display: none; }
      .table { gap: 14px; }
      .device { display: grid; gap: 10px; }
      .num { text-align: left; }
      .folder { flex-direction: column; }
    }
  </style>
</head>
<body>
  <div class="wrap">
    <div class="hero">
      <div>
        <h1>PhotoSync Admin</h1>
        <div class="sub">Minimal view of devices and uploaded folders. Fast to scan, no visual noise.</div>
      </div>
      <div class="pill"><span class="dot"></span><span id="status">Loading</span></div>
    </div>

    <div class="stats">
      <div class="card"><div class="label">Devices</div><div class="value" id="devicesCount">-</div></div>
      <div class="card"><div class="label">Folders</div><div class="value" id="albumsCount">-</div></div>
      <div class="card"><div class="label">Files</div><div class="value" id="filesCount">-</div></div>
      <div class="card"><div class="label">Storage</div><div class="value" id="bytesCount">-</div></div>
    </div>

    <div class="table">
      <div class="row head">
        <div>Device</div><div>Albums</div><div>Files</div><div>Storage</div><div>Last seen</div>
      </div>
      <div id="devices"></div>
    </div>
  </div>

  <script>
    const fmtBytes = (bytes) => {
      if (!bytes) return '0 B';
      const units = ['B', 'KB', 'MB', 'GB', 'TB'];
      let value = bytes;
      let unit = 0;
      while (value >= 1024 && unit < units.length - 1) {
        value /= 1024;
        unit++;
      }
      return `${value.toFixed(value >= 10 || unit === 0 ? 0 : 1)} ${units[unit]}`;
    };
    const fmtDate = (value) => new Date(value).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' });
    fetch('/api/admin/dashboard')
      .then(r => r.json())
      .then(data => {
        document.getElementById('status').textContent = 'Live';
        document.getElementById('devicesCount').textContent = data.device_count;
        document.getElementById('albumsCount').textContent = data.album_count;
        document.getElementById('filesCount').textContent = data.file_count;
        document.getElementById('bytesCount').textContent = fmtBytes(data.total_bytes);
        document.getElementById('devices').innerHTML = data.devices.map(device => `
          <div class="device row">
            <div>
              <div class="name">${device.device_name}</div>
              <div class="meta">${device.device_uuid}</div>
              <div class="tree">
                ${device.albums.map(album => `
                  <div class="folder">
                    <div>
                      <div class="folder-name">${album.name}</div>
                      <div class="folder-path">${album.relative_path}</div>
                    </div>
                    <div class="folder-stats">${album.file_count} files · ${fmtBytes(album.bytes_uploaded)}</div>
                  </div>
                `).join('')}
              </div>
            </div>
            <div class="num">${device.album_count}</div>
            <div class="num">${device.file_count}</div>
            <div class="num">${fmtBytes(device.bytes_uploaded)}</div>
            <div class="num">${fmtDate(device.last_seen_at)}</div>
          </div>
        `).join('');
      })
      .catch(() => {
        document.getElementById('status').textContent = 'Offline';
      });
  </script>
</body>
</html>
""";
}
