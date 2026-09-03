import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const source = path.join(process.cwd(), 'scripts', 'build-publish-release.mjs');
const temp = path.join(os.tmpdir(), `photosync-publish-0.6.2-${process.pid}.mjs`);

let code = fs.readFileSync(source, 'utf8');
code = code
  .replace("const VERSION = '0.6.1-beta';", "const VERSION = '0.6.2-beta';")
  .replace('const VERSION_CODE = 7;', 'const VERSION_CODE = 8;')
  .replace("const PREVIOUS_VERSION = '0.6.0-beta';", "const PREVIOUS_VERSION = '0.6.1-beta';");

if (!code.includes("const VERSION = '0.6.2-beta';") || !code.includes('const VERSION_CODE = 8;')) {
  throw new Error('Could not prepare PhotoSync 0.6.2 release script');
}

fs.writeFileSync(temp, code, 'utf8');
try {
  const result = spawnSync(process.execPath, [temp], {
    cwd: process.cwd(),
    env: process.env,
    stdio: 'inherit',
    timeout: 30 * 60 * 1000,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`PhotoSync 0.6.2 publication failed with exit code ${result.status}`);
} finally {
  fs.rmSync(temp, { force: true });
}
