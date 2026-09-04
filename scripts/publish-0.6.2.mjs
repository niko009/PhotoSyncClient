import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';

const VERSION = '0.6.2-beta';
const VERSION_CODE = 8;
const PREVIOUS_VERSION = '0.6.1-beta';
const repoRoot = process.cwd();
const gitCredentialFile = '/etc/bacus/git-credentials';
const source = path.join(repoRoot, 'scripts', 'build-publish-release.mjs');
const temp = path.join(os.tmpdir(), `photosync-publish-0.6.2-${process.pid}.mjs`);
const targetSigningDir = path.join(repoRoot, 'android', 'signing');
const targetSigningProperties = path.join(targetSigningDir, 'signing.properties');
const targetKeystore = path.join(targetSigningDir, 'photosync-release.jks');

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd ?? repoRoot,
    env: process.env,
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
    timeout: options.timeout ?? 120000,
  });
  if (result.error) throw result.error;
  if (result.status !== 0 && !options.allowFailure) {
    throw new Error(`${command} exited with ${result.status}: ${(result.stderr || result.stdout || '').slice(-5000)}`);
  }
  return result;
}

function normalizeRemote(value) {
  return String(value || '')
    .trim()
    .replace(/^git@github\.com:/, 'https://github.com/')
    .replace(/\.git$/, '')
    .replace(/\/$/, '');
}

function findRepo(fullName) {
  const wanted = `https://github.com/${fullName}`.toLowerCase();
  for (const root of ['/srv/bacus/repos', '/srv/bacus/control']) {
    if (!fs.existsSync(root)) continue;
    for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
      if (!entry.isDirectory()) continue;
      const candidate = path.join(root, entry.name);
      if (!fs.existsSync(path.join(candidate, '.git'))) continue;
      const remote = run('git', ['-C', candidate, 'remote', 'get-url', 'origin'], { allowFailure: true });
      if (remote.status === 0 && normalizeRemote(remote.stdout).toLowerCase() === wanted) return candidate;
    }
  }
  throw new Error(`Server checkout not found for ${fullName}`);
}

function gitArgs(repo, ...args) {
  return ['-c', `credential.helper=store --file=${gitCredentialFile}`, '-C', repo, ...args];
}

function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

function parseProperties(file) {
  const result = {};
  for (const raw of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq <= 0) continue;
    result[line.slice(0, eq).trim()] = line.slice(eq + 1).trim();
  }
  return result;
}

function walk(dir, depth = 0) {
  if (depth > 7 || !fs.existsSync(dir)) return [];
  const out = [];
  let entries = [];
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return out; }
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (['node_modules', '.git', 'dist', 'build', '.gradle'].includes(entry.name)) continue;
      out.push(...walk(full, depth + 1));
    } else if (entry.isFile() && entry.name === 'signing.properties') {
      out.push(full);
    }
  }
  return out;
}

function findUsableSigningConfig() {
  const roots = ['/srv/bacus', '/home/mbacus', '/etc/bacus'];
  const candidates = [...new Set(roots.flatMap(root => walk(root)))];
  for (const file of candidates) {
    if (path.resolve(file) === path.resolve(targetSigningProperties)) continue;
    try {
      const properties = parseProperties(file);
      if (!properties.storeFile || !properties.storePassword || !properties.keyAlias || !properties.keyPassword) continue;
      const storePath = path.resolve(path.dirname(file), properties.storeFile);
      if (!fs.existsSync(storePath) || !fs.statSync(storePath).isFile()) continue;
      return { file, storePath, properties };
    } catch {}
  }
  throw new Error('No usable PhotoSync signing.properties + keystore pair was found on the server');
}

function stageSigningConfig() {
  if (fs.existsSync(targetSigningProperties)) return { staged: false };
  const sourceConfig = findUsableSigningConfig();
  fs.mkdirSync(targetSigningDir, { recursive: true, mode: 0o700 });
  fs.copyFileSync(sourceConfig.storePath, targetKeystore);
  fs.chmodSync(targetKeystore, 0o600);
  const content = [
    'storeFile=photosync-release.jks',
    `storePassword=${sourceConfig.properties.storePassword}`,
    `keyAlias=${sourceConfig.properties.keyAlias}`,
    `keyPassword=${sourceConfig.properties.keyPassword}`,
    '',
  ].join('\n');
  fs.writeFileSync(targetSigningProperties, content, { encoding: 'utf8', mode: 0o600 });
  return { staged: true, sourceProperties: sourceConfig.file };
}

function cleanupSigningConfig(stage) {
  if (!stage?.staged) return;
  fs.rmSync(targetSigningProperties, { force: true });
  fs.rmSync(targetKeystore, { force: true });
  try { fs.rmdirSync(targetSigningDir); } catch {}
}

function publishLatestManifest() {
  const bacusRepo = findRepo('niko009/bacus.dev');
  const fileName = `photosync-android-${VERSION}.apk`;
  const apkRelative = path.join('public', 'downloads', 'photosync', fileName);
  const manifestRelative = path.join('public', 'downloads', 'photosync', 'latest.json');

  for (let attempt = 1; attempt <= 3; attempt++) {
    run('git', gitArgs(bacusRepo, 'fetch', '--prune', 'origin', 'main'), { cwd: bacusRepo });
    run('git', ['-C', bacusRepo, 'reset', '--hard', 'origin/main'], { cwd: bacusRepo });

    const apk = path.join(bacusRepo, apkRelative);
    if (!fs.existsSync(apk)) throw new Error(`Published APK is missing: ${apkRelative}`);
    const hash = sha256(apk);
    const sizeBytes = fs.statSync(apk).size;
    const manifest = {
      versionCode: VERSION_CODE,
      versionName: VERSION,
      apkUrl: `https://bacus.dev/downloads/photosync/${fileName}`,
      sha256: hash,
      sizeBytes,
    };
    const manifestPath = path.join(bacusRepo, manifestRelative);
    fs.writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');

    run('git', ['-C', bacusRepo, 'add', '--', manifestRelative]);
    const status = run('git', ['-C', bacusRepo, 'status', '--porcelain', '--', manifestRelative]);
    if (!status.stdout.trim()) return manifest;

    run('git', ['-C', bacusRepo, 'config', 'user.name', 'Bacus Release Bot']);
    run('git', ['-C', bacusRepo, 'config', 'user.email', 'bacus-release@users.noreply.github.com']);
    run('git', ['-C', bacusRepo, 'commit', '-m', `release: publish PhotoSync ${VERSION} update manifest`]);
    const push = run('git', gitArgs(bacusRepo, 'push', 'origin', 'HEAD:main'), { cwd: bacusRepo, allowFailure: true });
    if (push.status === 0) return manifest;
    if (attempt === 3) throw new Error(`Could not push PhotoSync ${VERSION} update manifest: ${(push.stderr || push.stdout || '').slice(-5000)}`);
  }
}

let code = fs.readFileSync(source, 'utf8');
code = code
  .replace("const VERSION = '0.6.1-beta';", `const VERSION = '${VERSION}';`)
  .replace('const VERSION_CODE = 7;', `const VERSION_CODE = ${VERSION_CODE};`)
  .replace("const PREVIOUS_VERSION = '0.6.0-beta';", `const PREVIOUS_VERSION = '${PREVIOUS_VERSION}';`);

if (!code.includes(`const VERSION = '${VERSION}';`) || !code.includes(`const VERSION_CODE = ${VERSION_CODE};`)) {
  throw new Error('Could not prepare PhotoSync 0.6.2 release script');
}

const signingStage = stageSigningConfig();
fs.writeFileSync(temp, code, 'utf8');
try {
  const result = spawnSync(process.execPath, [temp], {
    cwd: repoRoot,
    env: process.env,
    stdio: 'inherit',
    timeout: 30 * 60 * 1000,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`PhotoSync 0.6.2 publication failed with exit code ${result.status}`);

  const manifest = publishLatestManifest();
  console.log(JSON.stringify({ updateManifestPublished: true, signingConfigStaged: signingStage.staged, ...manifest }));
} finally {
  fs.rmSync(temp, { force: true });
  cleanupSigningConfig(signingStage);
}
