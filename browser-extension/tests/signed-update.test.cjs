const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const root = path.resolve(__dirname, '..');
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'manifest.json'), 'utf8'));

assert.match(manifest.version, /^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/, 'manifest.version must be semver-like');
assert.ok(manifest.key, 'Chromium manifest must contain a fixed public key');
assert.equal(
  manifest.update_url,
  'https://raw.githubusercontent.com/dulo11/fanyireal-time/extension-update-channel/updates.xml'
);

const der = Buffer.from(manifest.key, 'base64');
const digest = crypto.createHash('sha256').update(der).digest().subarray(0, 16);
const alphabet = 'abcdefghijklmnop';
let extensionId = '';
for (const byte of digest) extensionId += alphabet[byte >> 4] + alphabet[byte & 15];

assert.equal(extensionId, 'hlfnagdelcpfdbpdeackdjelemaaoban');
console.log(`signed update ID: ${extensionId}`);
