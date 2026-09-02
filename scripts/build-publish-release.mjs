import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';

const VERSION = '0.6.1-beta';
const VERSION_CODE = 7;
const PREVIOUS_VERSION = '0.6.0-beta';
const repoRoot = process.cwd();
const signingProperties = path.join(repoRoot, 'android', 'signing', 'signing.properties');
const gradleFile = path.join(repoRoot, 'android', 'app', 'build.gradle.kts');
const gradlew = path.join(repoRoot, 'gradlew');
const builtApk = path.join(repoRoot, 'android', 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk');
const gitCredentialFile = '/etc/bacus/git-credentials';

function fail(message, details = '') {
  if (details) console.error(details.slice(-5000));
  throw new Error(message);
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd ?? repoRoot,
    env: options.env ?? process.env,
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
    timeout: options.timeout ?? 120000,
  });
  if (result.error) fail(`${command} failed to start`, String(result.error));
  if (result.status !== 0 && !options.allowFailure) {
    fail(`${command} exited with ${result.status}`, `${result.stdout || ''}\n${result.stderr || ''}`);
  }
  return result;
}

function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
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
  const roots = ['/srv/bacus/repos', '/srv/bacus/control'];
  for (const root of roots) {
    if (!fs.existsSync(root)) continue;
    for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
      if (!entry.isDirectory()) continue;
      const candidate = path.join(root, entry.name);
      if (!fs.existsSync(path.join(candidate, '.git'))) continue;
      const remote = run('git', ['-C', candidate, 'remote', 'get-url', 'origin'], { allowFailure: true });
      if (remote.status === 0 && normalizeRemote(remote.stdout).toLowerCase() === wanted) return candidate;
    }
  }
  fail(`Server checkout not found for ${fullName}`);
}

function gitArgs(repo, ...args) {
  return ['-c', `credential.helper=store --file=${gitCredentialFile}`, '-C', repo, ...args];
}

function parseLocalPropertiesSdk() {
  const file = path.join(repoRoot, 'android', 'local.properties');
  if (!fs.existsSync(file)) return null;
  const line = fs.readFileSync(file, 'utf8').split(/\r?\n/).find(v => v.startsWith('sdk.dir='));
  if (!line) return null;
  return line.slice('sdk.dir='.length).replace(/\\:/g, ':').replace(/\\\\/g, '\\');
}

function findSdk() {
  const candidates = [
    process.env.ANDROID_HOME,
    process.env.ANDROID_SDK_ROOT,
    parseLocalPropertiesSdk(),
    '/opt/android-sdk',
    '/usr/lib/android-sdk',
    '/srv/bacus/android-sdk',
    '/srv/android-sdk',
  ].filter(Boolean);
  return candidates.find(candidate => fs.existsSync(path.join(candidate, 'build-tools'))) || null;
}

function findApkSigner(sdk) {
  const fromPath = run('which', ['apksigner'], { allowFailure: true });
  if (fromPath.status === 0 && fromPath.stdout.trim()) return fromPath.stdout.trim();
  if (!sdk) return null;
  const root = path.join(sdk, 'build-tools');
  const versions = fs.readdirSync(root, { withFileTypes: true })
    .filter(e => e.isDirectory())
    .map(e => e.name)
    .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
  for (const version of versions) {
    const candidate = path.join(root, version, 'apksigner');
    if (fs.existsSync(candidate)) return candidate;
  }
  return null;
}

function certDigest(apksigner, apk) {
  const result = run(apksigner, ['verify', '--print-certs', apk]);
  const match = `${result.stdout}\n${result.stderr}`.match(/certificate SHA-256 digest:\s*([0-9a-fA-F:]+)/i);
  if (!match) fail('Could not read APK certificate SHA-256 digest');
  return match[1].replace(/:/g, '').toLowerCase();
}

function assertReleaseConfig() {
  if (!fs.existsSync(signingProperties)) fail('android/signing/signing.properties is missing on the server');
  if (!fs.existsSync(gradlew)) fail('Gradle wrapper is missing');
  const gradle = fs.readFileSync(gradleFile, 'utf8');
  if (!gradle.includes(`versionCode = ${VERSION_CODE}`) || !gradle.includes(`versionName = "${VERSION}"`)) {
    fail(`Android version is not ${VERSION} / versionCode ${VERSION_CODE}`);
  }
}

function buildRelease() {
  assertReleaseConfig();
  fs.chmodSync(gradlew, 0o755);
  const sdk = findSdk();
  const env = { ...process.env };
  if (sdk) {
    env.ANDROID_HOME = sdk;
    env.ANDROID_SDK_ROOT = sdk;
  }
  const build = run(gradlew, ['--no-daemon', ':android:app:assembleRelease'], {
    env,
    timeout: 25 * 60 * 1000,
  });
  if (!fs.existsSync(builtApk)) fail('Release APK was not produced', build.stdout);
  return { sdk };
}

function updateProjectMetadata(bacusRepo, apkHash, apkSize) {
  const projectsPath = path.join(bacusRepo, 'src', 'data', 'projects.json');
  const projects = JSON.parse(fs.readFileSync(projectsPath, 'utf8'));
  const project = projects.find(item => item.slug === 'photosync');
  if (!project) fail('PhotoSync project metadata not found in bacus.dev');
  const fileName = `photosync-android-${VERSION}.apk`;
  const url = `/downloads/photosync/${fileName}`;
  project.descriptionEn = `PhotoSync ${VERSION} fixes empty-album synchronization state and storage-backed folder creation while keeping Gallery share import and per-folder family View/Contribute controls. The Android client uses the permanent Bacus Lab release certificate and updates existing release-signed installations in place.`;
  project.descriptionRu = `PhotoSync ${VERSION} исправляет статус синхронизации пустых альбомов и создание физических папок в серверном хранилище, сохраняя импорт через «Поделиться» и семейный доступ View/Contribute для каждой папки. Android-клиент подписан постоянным release-сертификатом Bacus Lab и обновляет существующие release-версии поверх установленного приложения.`;
  project.downloads.android = {
    url,
    version: VERSION,
    size: `${(apkSize / 1_000_000).toFixed(2)} MB`,
    sha256: apkHash,
  };
  fs.writeFileSync(projectsPath, `${JSON.stringify(projects, null, 2)}\n`);
  return { fileName, url, projectsPath };
}

function updateProjectDoc(bacusRepo, apkHash, apkSize, fileName) {
  const docPath = path.join(bacusRepo, 'docs', 'projects', 'photosync.md');
  let doc = fs.readFileSync(docPath, 'utf8');
  doc = doc.replace(/## Current release: .*\n/, `## Current release: ${VERSION}\n`);
  doc = doc.replace(/Public artifact: `[^`]+`\./, `Public artifact: \`/downloads/photosync/${fileName}\`.`);
  doc = doc.replace(/Size: [^\n]+/, `Size: ${apkSize.toLocaleString('en-US')} bytes (${(apkSize / 1_000_000).toFixed(2)} MB).`);
  doc = doc.replace(/SHA-256: `[0-9a-f]+`\./, `SHA-256: \`${apkHash}\`.`);
  doc = doc.replace(/all current page download links point to [^\.]+\./, `all current page download links point to ${VERSION.replace('-beta', '')}.`);
  doc = doc.replace(/- 0\.6\.0 uses the same permanent certificate as 0\.3\.0, 0\.4\.0 and 0\.5\.0 and updates them in place\./,
    `- ${VERSION.replace('-beta', '')} uses the same permanent certificate as 0.3.0, 0.4.0, 0.5.0 and 0.6.0 and updates them in place.`);
  doc = doc.replace(/Verified: 2026-09-02 against the locally tested, release-signed [^ ]+ APK\./,
    `Verified: 2026-09-02 against the server-built, release-signed ${VERSION} APK.`);
  if (!doc.includes('0.6.1 fixes empty-album')) {
    doc = doc.replace(`## Current release: ${VERSION}\n`, `## Current release: ${VERSION}\n\n0.6.1 fixes empty-album sync reporting, prevents ghost albums when storage is unavailable, and restores missing physical album directories on server startup.\n`);
  }
  fs.writeFileSync(docPath, doc);
  return docPath;
}

function updateVerifier(bacusRepo, apkHash, apkSize, fileName, url) {
  const verifierPath = path.join(bacusRepo, 'scripts', 'verify-photosync-release.mjs');
  const content = `import assert from 'node:assert/strict';\nimport { createHash } from 'node:crypto';\nimport { readFileSync } from 'node:fs';\n\nconst read = (path) => readFileSync(new URL('../' + path, import.meta.url));\nconst projects = JSON.parse(read('src/data/projects.json'));\nconst project = projects.find(({ slug }) => slug === 'photosync');\nconst android = project.downloads.android;\nconst expectedHash = '${apkHash}';\nconst expectedSize = ${apkSize};\nconst expectedVersion = '${VERSION}';\nconst expectedFile = '${fileName}';\nconst sha256 = (data) => createHash('sha256').update(data).digest('hex');\nassert.equal(android.version, expectedVersion);\nassert.equal(android.sha256, expectedHash);\nassert.equal(android.url, '${url}');\nassert.equal(project.factory.managed, true);\nassert.equal(project.factory.repository, 'niko009/PhotoSyncClient');\nassert.equal(project.factory.domain, 'photosync.bacus.dev');\nfor (const folder of ['public', 'dist']) {\n  const apk = read(folder + android.url);\n  assert.equal(apk.length, expectedSize);\n  assert.equal(sha256(apk), expectedHash, folder + ' APK hash');\n  assert.equal(read(folder + android.url + '.sha256').toString().trim(), expectedHash + '  ' + expectedFile);\n}\nconst page = read('dist/projects/photosync/index.html').toString();\nassert.ok(page.includes(expectedVersion));\nconst downloads = [...page.matchAll(/href=\\\"([^\\\"]+\\.apk\\?v=[^\\\"]+)\\\"/g)].map((match) => match[1]);\nassert.equal(downloads.length, 3);\nassert.ok(downloads.every((value) => value === android.url + '?v=' + expectedHash.slice(0, 12) + '-r1'));\nassert.ok(page.includes('release-подпись'));\nassert.ok(page.includes('Google-вход'));\nfor (const file of ['01-albums', '02-sync', '03-folder', '04-viewer', '05-settings']) {\n  const imagePath = '/images/photosync/0.2.0-beta/' + file + '.png';\n  const original = read('public' + imagePath);\n  assert.equal(original.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');\n  assert.equal(sha256(read('dist' + imagePath)), sha256(original));\n}\nconsole.log('PhotoSync release verified:', expectedVersion, expectedHash);\n`;
  fs.writeFileSync(verifierPath, content);
  return verifierPath;
}

function applyPublication(bacusRepo, apkHash, apkSize) {
  const { fileName, url, projectsPath } = updateProjectMetadata(bacusRepo, apkHash, apkSize);
  const downloadDir = path.join(bacusRepo, 'public', 'downloads', 'photosync');
  fs.mkdirSync(downloadDir, { recursive: true });
  const targetApk = path.join(downloadDir, fileName);
  fs.copyFileSync(builtApk, targetApk);
  fs.writeFileSync(`${targetApk}.sha256`, `${apkHash}  ${fileName}\n`);
  const docPath = updateProjectDoc(bacusRepo, apkHash, apkSize, fileName);
  const verifierPath = updateVerifier(bacusRepo, apkHash, apkSize, fileName, url);
  return { fileName, url, targetApk, projectsPath, docPath, verifierPath };
}

function syncRepo(repo) {
  run('git', gitArgs(repo, 'fetch', '--prune', 'origin', 'main'), { cwd: repo });
  run('git', ['-C', repo, 'reset', '--hard', 'origin/main'], { cwd: repo });
}

function publishToBacusDev(bacusRepo, apkHash, apkSize, apksigner, previousCert) {
  for (let attempt = 1; attempt <= 3; attempt++) {
    syncRepo(bacusRepo);
    const oldApk = path.join(bacusRepo, 'public', 'downloads', 'photosync', `photosync-android-${PREVIOUS_VERSION}.apk`);
    if (!fs.existsSync(oldApk)) fail(`Previous release ${PREVIOUS_VERSION} not found`);
    const oldCert = certDigest(apksigner, oldApk);
    if (oldCert !== previousCert) fail('Published previous APK certificate changed during release');
    const files = applyPublication(bacusRepo, apkHash, apkSize);
    run('git', ['-C', bacusRepo, 'add', '--',
      path.relative(bacusRepo, files.targetApk),
      path.relative(bacusRepo, `${files.targetApk}.sha256`),
      path.relative(bacusRepo, files.projectsPath),
      path.relative(bacusRepo, files.docPath),
      path.relative(bacusRepo, files.verifierPath),
    ]);
    const status = run('git', ['-C', bacusRepo, 'status', '--porcelain']);
    if (!status.stdout.trim()) return { ...files, commit: run('git', ['-C', bacusRepo, 'rev-parse', 'HEAD']).stdout.trim(), alreadyPublished: true };
    run('git', ['-C', bacusRepo, 'config', 'user.name', 'Bacus Release Bot']);
    run('git', ['-C', bacusRepo, 'config', 'user.email', 'bacus-release@users.noreply.github.com']);
    run('git', ['-C', bacusRepo, 'commit', '-m', `release: publish PhotoSync ${VERSION}`]);
    const commit = run('git', ['-C', bacusRepo, 'rev-parse', 'HEAD']).stdout.trim();
    const push = run('git', gitArgs(bacusRepo, 'push', 'origin', 'HEAD:main'), { cwd: bacusRepo, allowFailure: true });
    if (push.status === 0) return { ...files, commit, alreadyPublished: false };
    if (attempt === 3) fail('Could not push bacus.dev release after retries', `${push.stdout}\n${push.stderr}`);
  }
}

const { sdk } = buildRelease();
const bacusRepo = findRepo('niko009/bacus.dev');
syncRepo(bacusRepo);
const apksigner = findApkSigner(sdk);
if (!apksigner) fail('Android apksigner was not found on the server');
const previousApk = path.join(bacusRepo, 'public', 'downloads', 'photosync', `photosync-android-${PREVIOUS_VERSION}.apk`);
if (!fs.existsSync(previousApk)) fail(`Previous public APK ${PREVIOUS_VERSION} is missing`);
const previousCert = certDigest(apksigner, previousApk);
const newCert = certDigest(apksigner, builtApk);
if (newCert !== previousCert) fail('Release certificate mismatch: refusing to publish');
const apkHash = sha256(builtApk);
const apkSize = fs.statSync(builtApk).size;
const published = publishToBacusDev(bacusRepo, apkHash, apkSize, apksigner, previousCert);
console.log(JSON.stringify({
  version: VERSION,
  versionCode: VERSION_CODE,
  certificateMatchesPreviousRelease: true,
  certificateSha256: newCert,
  apkSha256: apkHash,
  apkBytes: apkSize,
  publicUrl: `https://bacus.dev${published.url}`,
  bacusDevCommit: published.commit,
  alreadyPublished: published.alreadyPublished,
}));
