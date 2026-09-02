import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const repoRoot = process.cwd();
const candidateRoots = [
  path.join(repoRoot, 'android', 'signing'),
  '/srv/bacus/apps/photosync',
  '/srv/bacus/repos/photosync',
  '/srv/bacus/control/photosync',
].filter((value, index, list) => list.indexOf(value) === index);

function walk(dir, depth = 0) {
  if (depth > 4 || !fs.existsSync(dir)) return [];
  const out = [];
  let entries = [];
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return out; }
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...walk(full, depth + 1));
    else if (entry.isFile() && entry.name === 'signing.properties') out.push(full);
  }
  return out;
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

const propertyFiles = [...new Set(candidateRoots.flatMap(root => walk(root)))];
const usable = [];
for (const file of propertyFiles) {
  try {
    const p = parseProperties(file);
    if (!p.storeFile || !p.storePassword || !p.keyAlias || !p.keyPassword) continue;
    const storePath = path.resolve(path.dirname(file), p.storeFile);
    if (!fs.existsSync(storePath) || !fs.statSync(storePath).isFile()) continue;
    usable.push({ file, storePath });
  } catch {}
}

const java = spawnSync('java', ['-version'], { encoding: 'utf8' });
const gradleWrapper = path.join(repoRoot, 'gradlew');
const sdkCandidates = [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT, '/opt/android-sdk', '/usr/lib/android-sdk']
  .filter(Boolean);
const sdkAvailable = sdkCandidates.some(candidate => fs.existsSync(candidate));

console.log(JSON.stringify({
  signingPropertiesFound: propertyFiles.length > 0,
  usableSigningConfigCount: usable.length,
  javaAvailable: java.status === 0,
  gradleWrapperAvailable: fs.existsSync(gradleWrapper),
  androidSdkAvailable: sdkAvailable,
  readyForReleaseBuild: usable.length === 1 && java.status === 0 && fs.existsSync(gradleWrapper) && sdkAvailable,
}));

if (usable.length !== 1) process.exitCode = 2;
