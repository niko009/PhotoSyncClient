import fs from 'node:fs/promises';
import https from 'node:https';
import path from 'node:path';

const mountPoint = '/mnt/server';
const result = {
  mountPoint,
  mounted: false,
  fileSystem: null,
  writable: false,
  healthStatus: null,
  healthOk: false,
};

function decodeMountField(value) {
  return value
    .replaceAll('\\040', ' ')
    .replaceAll('\\011', '\t')
    .replaceAll('\\012', '\n')
    .replaceAll('\\134', '\\');
}

async function inspectMount() {
  const mounts = await fs.readFile('/proc/mounts', 'utf8');
  for (const line of mounts.split('\n')) {
    if (!line.trim()) continue;
    const fields = line.split(' ');
    if (fields.length < 3) continue;
    if (decodeMountField(fields[1]) !== mountPoint) continue;
    result.mounted = true;
    result.fileSystem = fields[2];
    return;
  }
}

async function verifyWrite() {
  const testFile = path.join(mountPoint, `.photosync-storage-check-${process.pid}-${Date.now()}`);
  try {
    await fs.writeFile(testFile, 'PhotoSync storage check\n', { flag: 'wx' });
    result.writable = true;
  } finally {
    await fs.rm(testFile, { force: true }).catch(() => {});
  }
}

function checkHealth() {
  return new Promise((resolve, reject) => {
    const request = https.get('https://photosync.bacus.dev/health', {
      timeout: 10000,
      headers: { 'User-Agent': 'bacus-agent-photosync-storage-check/1.0' },
    }, response => {
      result.healthStatus = response.statusCode ?? null;
      let body = '';
      response.setEncoding('utf8');
      response.on('data', chunk => { if (body.length < 4096) body += chunk; });
      response.on('end', () => {
        result.healthOk = response.statusCode === 200 && body.includes('"status":"ok"');
        resolve();
      });
    });
    request.on('timeout', () => request.destroy(new Error('health_timeout')));
    request.on('error', reject);
  });
}

const errors = [];

try { await inspectMount(); }
catch (error) { errors.push(`mount_check: ${error.message}`); }

if (!result.mounted) {
  errors.push('mount_check: /mnt/server is not a mount point');
} else if (result.fileSystem !== 'vboxsf') {
  errors.push(`mount_check: expected vboxsf, got ${result.fileSystem}`);
}

try { await verifyWrite(); }
catch (error) { errors.push(`write_check: ${error.message}`); }

try { await checkHealth(); }
catch (error) { errors.push(`health_check: ${error.message}`); }

if (!result.healthOk && !errors.some(value => value.startsWith('health_check:'))) {
  errors.push(`health_check: unexpected HTTP ${result.healthStatus}`);
}

console.log(JSON.stringify({ ...result, ok: errors.length === 0, errors }, null, 2));
if (errors.length > 0) process.exitCode = 1;
