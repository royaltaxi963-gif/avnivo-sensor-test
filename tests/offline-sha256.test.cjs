/*
 * Node test: verify AVNIVO streaming SHA-256 against native crypto across chunk boundaries.
 * Model files are hundreds of MB and are hashed incrementally, so a boundary bug would silently
 * accept a corrupt download. This covers the packaged installer asset directly.
 */
const fs = require('node:fs');
const vm = require('node:vm');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const path = require('node:path');

const source = fs.readFileSync(
  path.join(__dirname, '..', 'android-app', 'app', 'src', 'main', 'assets', 'offline-model-installer.js'),
  'utf8'
);
const start = source.indexOf('class SHA256 {');
const end = source.indexOf('async function ticket(');
assert(start >= 0 && end > start, 'SHA256 source missing from the packaged installer');

const SHA256 = vm.runInNewContext(source.slice(start, end) + '; SHA256');

for (const n of [0, 1, 3, 55, 56, 63, 64, 65, 127, 128, 129, 4096, 1048579]) {
  const bytes = crypto.randomBytes(n);
  const hasher = new SHA256();
  for (let i = 0; i < bytes.length; i += 17) hasher.update(bytes.subarray(i, i + 17));
  assert.equal(
    hasher.finish(),
    crypto.createHash('sha256').update(bytes).digest('hex'),
    'size ' + n
  );
}

console.log('SHA256 streaming vectors passed (13 cases)');
