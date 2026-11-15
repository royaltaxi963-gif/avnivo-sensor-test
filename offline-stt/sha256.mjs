/*
 * Incremental SHA-256.
 *
 * crypto.subtle.digest() needs the whole buffer at once, which is unsafe for the
 * ~150 MB decoder on a phone. This implementation hashes a stream chunk by chunk so
 * model files can be verified while they download without buffering them twice.
 */

const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
]);

const INITIAL = new Uint32Array([
  0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
]);

export function createSha256() {
  const h = INITIAL.slice();
  const block = new Uint8Array(64);
  const w = new Uint32Array(64);
  let blockLen = 0;
  let total = 0;

  const rotr = (x, n) => ((x >>> n) | (x << (32 - n))) >>> 0;

  function processBlock(bytes) {
    for (let i = 0; i < 16; i++) {
      w[i] = ((bytes[i * 4] << 24) | (bytes[i * 4 + 1] << 16) | (bytes[i * 4 + 2] << 8) | bytes[i * 4 + 3]) >>> 0;
    }
    for (let i = 16; i < 64; i++) {
      const s0 = (rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3)) >>> 0;
      const s1 = (rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10)) >>> 0;
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
    }
    let [a, b, c, d, e, f, g, hh] = h;
    for (let i = 0; i < 64; i++) {
      const S1 = (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) >>> 0;
      const ch = ((e & f) ^ (~e & g)) >>> 0;
      const t1 = (hh + S1 + ch + K[i] + w[i]) >>> 0;
      const S0 = (rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) >>> 0;
      const maj = ((a & b) ^ (a & c) ^ (b & c)) >>> 0;
      const t2 = (S0 + maj) >>> 0;
      hh = g; g = f; f = e; e = (d + t1) >>> 0;
      d = c; c = b; b = a; a = (t1 + t2) >>> 0;
    }
    h[0] = (h[0] + a) >>> 0; h[1] = (h[1] + b) >>> 0; h[2] = (h[2] + c) >>> 0; h[3] = (h[3] + d) >>> 0;
    h[4] = (h[4] + e) >>> 0; h[5] = (h[5] + f) >>> 0; h[6] = (h[6] + g) >>> 0; h[7] = (h[7] + hh) >>> 0;
  }

  return {
    update(chunk) {
      total += chunk.length;
      let offset = 0;
      if (blockLen > 0) {
        const need = Math.min(64 - blockLen, chunk.length);
        block.set(chunk.subarray(0, need), blockLen);
        blockLen += need;
        offset += need;
        if (blockLen === 64) {
          processBlock(block);
          blockLen = 0;
        }
      }
      while (offset + 64 <= chunk.length) {
        processBlock(chunk.subarray(offset, offset + 64));
        offset += 64;
      }
      if (offset < chunk.length) {
        block.set(chunk.subarray(offset), 0);
        blockLen = chunk.length - offset;
      }
      return this;
    },
    digestHex() {
      const bitLenHi = Math.floor(total / 0x20000000) >>> 0;
      const bitLenLo = (total << 3) >>> 0;
      const tail = new Uint8Array(blockLen < 56 ? 64 : 128);
      tail.set(block.subarray(0, blockLen));
      tail[blockLen] = 0x80;
      const view = new DataView(tail.buffer);
      view.setUint32(tail.length - 8, bitLenHi);
      view.setUint32(tail.length - 4, bitLenLo);
      for (let i = 0; i < tail.length; i += 64) processBlock(tail.subarray(i, i + 64));
      let out = '';
      for (let i = 0; i < 8; i++) out += h[i].toString(16).padStart(8, '0');
      return out;
    }
  };
}

export function sha256Hex(bytes) {
  return createSha256().update(bytes).digestHex();
}
