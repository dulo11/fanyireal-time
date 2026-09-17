const assert = require('node:assert/strict');

if (typeof globalThis.btoa !== 'function') {
  globalThis.btoa = value => Buffer.from(value, 'binary').toString('base64');
}
require('../shared/crypto-lite.js');

const FT = globalThis.FTCryptoLite;
assert.ok(FT, 'FTCryptoLite should be exported');
assert.equal(FT.md5Hex(''), 'd41d8cd98f00b204e9800998ecf8427e');
assert.equal(FT.md5Hex('abc'), '900150983cd24fb0d6963f7d28e17f72');
assert.equal(FT.md5Hex('hello'), '5d41402abc4b2a76b9719d911017c592');
assert.equal(FT.md5Base64('abc'), 'kAFQmDzST7DWlj99KOF/cg==');

console.log('crypto-lite tests passed');