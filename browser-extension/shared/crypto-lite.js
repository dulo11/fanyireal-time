(() => {
  if (globalThis.FTCryptoLite) return;

  const encoder = new TextEncoder();

  function toBytes(input) {
    if (input instanceof Uint8Array) return input;
    if (input instanceof ArrayBuffer) return new Uint8Array(input);
    return encoder.encode(String(input ?? ""));
  }

  function leftRotate(value, shift) {
    return ((value << shift) | (value >>> (32 - shift))) >>> 0;
  }

  function md5Bytes(input) {
    const source = toBytes(input);
    const bitLength = source.length * 8;
    const totalLength = (((source.length + 9 + 63) >> 6) << 6);
    const bytes = new Uint8Array(totalLength);
    bytes.set(source);
    bytes[source.length] = 0x80;

    const view = new DataView(bytes.buffer);
    view.setUint32(totalLength - 8, bitLength >>> 0, true);
    view.setUint32(totalLength - 4, Math.floor(bitLength / 0x100000000) >>> 0, true);

    const shifts = [
      7,12,17,22, 7,12,17,22, 7,12,17,22, 7,12,17,22,
      5,9,14,20, 5,9,14,20, 5,9,14,20, 5,9,14,20,
      4,11,16,23, 4,11,16,23, 4,11,16,23, 4,11,16,23,
      6,10,15,21, 6,10,15,21, 6,10,15,21, 6,10,15,21
    ];
    const K = Array.from({ length: 64 }, (_, i) => Math.floor(Math.abs(Math.sin(i + 1)) * 0x100000000) >>> 0);

    let a0 = 0x67452301;
    let b0 = 0xefcdab89;
    let c0 = 0x98badcfe;
    let d0 = 0x10325476;

    for (let offset = 0; offset < totalLength; offset += 64) {
      const M = new Uint32Array(16);
      for (let i = 0; i < 16; i++) M[i] = view.getUint32(offset + i * 4, true);

      let a = a0, b = b0, c = c0, d = d0;
      for (let i = 0; i < 64; i++) {
        let f, g;
        if (i < 16) {
          f = (b & c) | ((~b) & d);
          g = i;
        } else if (i < 32) {
          f = (d & b) | ((~d) & c);
          g = (5 * i + 1) % 16;
        } else if (i < 48) {
          f = b ^ c ^ d;
          g = (3 * i + 5) % 16;
        } else {
          f = c ^ (b | (~d));
          g = (7 * i) % 16;
        }
        const next = d;
        d = c;
        c = b;
        const sum = (a + (f >>> 0) + K[i] + M[g]) >>> 0;
        b = (b + leftRotate(sum, shifts[i])) >>> 0;
        a = next;
      }

      a0 = (a0 + a) >>> 0;
      b0 = (b0 + b) >>> 0;
      c0 = (c0 + c) >>> 0;
      d0 = (d0 + d) >>> 0;
    }

    const out = new Uint8Array(16);
    const outView = new DataView(out.buffer);
    outView.setUint32(0, a0, true);
    outView.setUint32(4, b0, true);
    outView.setUint32(8, c0, true);
    outView.setUint32(12, d0, true);
    return out;
  }

  function bytesToHex(bytes) {
    return [...bytes].map(byte => byte.toString(16).padStart(2, "0")).join("");
  }

  function bytesToBase64(bytes) {
    let binary = "";
    const chunk = 0x8000;
    for (let i = 0; i < bytes.length; i += chunk) {
      binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
    }
    return btoa(binary);
  }

  async function hmacSha1Base64(secret, message) {
    const key = await crypto.subtle.importKey(
      "raw",
      encoder.encode(String(secret ?? "")),
      { name: "HMAC", hash: "SHA-1" },
      false,
      ["sign"]
    );
    const signature = await crypto.subtle.sign("HMAC", key, encoder.encode(String(message ?? "")));
    return bytesToBase64(new Uint8Array(signature));
  }

  globalThis.FTCryptoLite = Object.freeze({
    md5Bytes,
    md5Hex: input => bytesToHex(md5Bytes(input)),
    md5Base64: input => bytesToBase64(md5Bytes(input)),
    bytesToHex,
    bytesToBase64,
    hmacSha1Base64
  });
})();