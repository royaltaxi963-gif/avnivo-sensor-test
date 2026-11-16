import { test } from 'node:test';
import assert from 'node:assert/strict';

import { createSha256, sha256Hex } from '../sha256.mjs';
import { OfflineStorage, MODEL_CACHE_NAME } from '../offline-storage.mjs';
import { ModelManager, MODEL_STATE, ModelError } from '../model-manager.mjs';
import { SHARED_MODEL, SHARED_MODEL_BYTES, MODEL_REVISION } from '../model-spec.mjs';
import { LANGUAGE_REGISTRY, usesWhisper, whisperLanguages, assertRegistryConsistent } from '../language-registry.mjs';
import { routeLanguage, ROUTE, appendTranscript, decideMicRoute, MIC_ACTION } from '../stt-router.mjs';
import { createMemoryCache } from './helpers.mjs';

// ---- sha256 ----------------------------------------------------------------

test('sha256 matches known vectors', () => {
  const enc = new TextEncoder();
  assert.equal(sha256Hex(enc.encode('')),
    'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
  assert.equal(sha256Hex(enc.encode('abc')),
    'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
});

test('sha256 is chunk-boundary independent', () => {
  const data = new Uint8Array(200000).map((_, i) => i % 251);
  const whole = sha256Hex(data);
  const incremental = createSha256();
  for (let i = 0; i < data.length; i += 63) incremental.update(data.subarray(i, i + 63));
  assert.equal(incremental.digestHex(), whole);
});

// ---- model spec ------------------------------------------------------------

test('model spec declares exact measured sizes and hashes', () => {
  assert.equal(SHARED_MODEL_BYTES, 251875316);
  assert.equal(MODEL_REVISION, '2d67713f236afa48a18992566e7647f6ca848e13');
  for (const file of SHARED_MODEL.files) {
    assert.match(file.sha256, /^[0-9a-f]{64}$/);
    assert.ok(file.bytes > 0);
  }
});

// ---- registry --------------------------------------------------------------

test('registry holds seven languages and stays consistent', () => {
  assert.equal(LANGUAGE_REGISTRY.length, 7);
  assert.equal(assertRegistryConsistent(), true);
  assert.equal(usesWhisper('uz'), false);
  assert.deepEqual(whisperLanguages().map((e) => e.code), ['ru', 'en', 'hi', 'bn', 'tr', 'tk']);
});

// ---- router ----------------------------------------------------------------

test('Uzbek routes to the custom module and never to Whisper', () => {
  assert.equal(routeLanguage('uz', { modelInstalled: true }).route, ROUTE.CUSTOM);
  assert.equal(routeLanguage('uz', { modelInstalled: false }).route, ROUTE.CUSTOM);
});

test('Whisper languages require the model to be installed', () => {
  for (const code of ['ru', 'en', 'hi', 'bn', 'tr', 'tk']) {
    assert.equal(routeLanguage(code, { modelInstalled: false }).route, ROUTE.UNAVAILABLE);
    const decision = routeLanguage(code, { modelInstalled: true });
    assert.equal(decision.route, ROUTE.WHISPER);
    assert.ok(decision.whisperLanguage);
  }
});

test('unknown language is rejected', () => {
  assert.equal(routeLanguage('xx', { modelInstalled: true }).route, ROUTE.UNAVAILABLE);
});

test('appendTranscript joins without duplicating separators', () => {
  assert.equal(appendTranscript('salom', 'dunyo'), 'salom dunyo');
  assert.equal(appendTranscript('', 'dunyo'), 'dunyo');
  assert.equal(appendTranscript('salom ', ' dunyo'), 'salom dunyo');
  assert.equal(appendTranscript('salom', ''), 'salom');
});

// ---- online/offline isolation and microphone routing -----------------------

test('online engine always keeps the microphone (Chirp 2 isolation)', () => {
  for (const code of ['uz', 'ru', 'en', 'hi', 'bn', 'tr', 'tk']) {
    const d = decideMicRoute({ languageCode: code, modelInstalled: true, onlineEngine: true });
    assert.equal(d.action, MIC_ACTION.HOST);
    assert.equal(d.reason, 'online_engine');
  }
});

test('Uzbek is handed back to the custom module in offline mode', () => {
  const d = decideMicRoute({ languageCode: 'uz', modelInstalled: true, onlineEngine: false });
  assert.equal(d.action, MIC_ACTION.HOST);
  assert.equal(d.reason, 'custom_module');
});

test('Whisper languages run offline only when the model is installed', () => {
  for (const code of ['ru', 'en', 'hi', 'bn', 'tr', 'tk']) {
    assert.equal(decideMicRoute({ languageCode: code, modelInstalled: false }).action, MIC_ACTION.BLOCK);
    const ready = decideMicRoute({ languageCode: code, modelInstalled: true });
    assert.equal(ready.action, MIC_ACTION.WHISPER);
    assert.ok(ready.whisperLanguage);
  }
});

test('offline mode blocks unsupported languages instead of substituting one', () => {
  const d = decideMicRoute({ languageCode: 'xx', modelInstalled: true });
  assert.equal(d.action, MIC_ACTION.BLOCK);
  assert.equal(d.reason, 'unknown_language');
});

test('languages measured as unreliable are flagged for the UI', () => {
  const poor = LANGUAGE_REGISTRY.filter((e) => e.quality === 'poor').map((e) => e.code);
  assert.deepEqual(poor.sort(), ['bn', 'tk']);
});

// ---- storage ---------------------------------------------------------------

test('offline storage round-trips through a cache-like backend', async () => {
  globalThis.caches = createMemoryCache();
  const storage = new OfflineStorage();
  assert.equal(storage.persistent, true);
  await storage.put('a', new Blob([new Uint8Array([1, 2, 3])]));
  assert.equal(await storage.has('a'), true);
  await storage.clear();
  assert.equal(await storage.has('a'), false);
  delete globalThis.caches;
});

// ---- model manager ---------------------------------------------------------

function makeStorage() {
  globalThis.caches = createMemoryCache();
  return new OfflineStorage();
}

test('model manager reports storage failure before downloading', async () => {
  const storage = makeStorage();
  const manager = new ModelManager({
    storage, model: SHARED_MODEL,
    fetchImpl: async () => ({ ok: true }),
    estimate: async () => ({ usage: 0, quota: 1e9, free: 1000 })
  });
  await assert.rejects(() => manager.ensureInstalled(), (e) => e instanceof ModelError && e.reason === 'storage');
  assert.equal(manager.state, MODEL_STATE.ERROR);
  delete globalThis.caches;
});

test('model manager rejects files whose bytes do not match the hash', async () => {
  const storage = makeStorage();
  const tiny = {
    id: 'test', baseUrl: 'https://example.test/', dtype: 'q8',
    files: [{ path: 'x.onnx', bytes: 3, sha256: 'a'.repeat(64) }]
  };
  const bytes = new Uint8Array([1, 2, 3]);
  const manager = new ModelManager({
    storage, model: tiny,
    fetchImpl: async () => ({ ok: true, arrayBuffer: async () => bytes.buffer.slice(0) }),
    estimate: async () => ({ free: 1e9, quota: 2e9, usage: 0 })
  });
  await assert.rejects(() => manager.ensureInstalled(), (e) => e instanceof ModelError && e.reason === 'integrity');
  delete globalThis.caches;
});

test('model manager accepts matching bytes, then skips re-download', async () => {
  const storage = makeStorage();
  const bytes = new TextEncoder().encode('hello-whisper');
  const model = {
    id: 'test', baseUrl: 'https://example.test/', dtype: 'q8',
    files: [{ path: 'x.bin', bytes: bytes.length, sha256: sha256Hex(bytes) }]
  };
  let calls = 0;
  const manager = new ModelManager({
    storage, model,
    fetchImpl: async () => { calls += 1; return { ok: true, arrayBuffer: async () => bytes.buffer.slice(0) }; },
    estimate: async () => ({ free: 1e9, quota: 2e9, usage: 0 })
  });
  await manager.ensureInstalled();
  assert.equal(manager.state, MODEL_STATE.READY);
  assert.equal(calls, 1);
  await manager.ensureInstalled();
  assert.equal(calls, 1);
  assert.equal((await manager.status()).installed, true);
  await manager.remove();
  assert.equal((await manager.status()).installed, false);
  delete globalThis.caches;
});

test('model manager resumes: only missing files are fetched', async () => {
  const storage = makeStorage();
  const a = new TextEncoder().encode('AAAA');
  const b = new TextEncoder().encode('BBBB');
  const model = {
    id: 'test', baseUrl: 'https://example.test/', dtype: 'q8',
    files: [
      { path: 'a.bin', bytes: a.length, sha256: sha256Hex(a) },
      { path: 'b.bin', bytes: b.length, sha256: sha256Hex(b) }
    ]
  };
  await storage.put('https://example.test/a.bin', new Blob([a]));
  const fetched = [];
  const manager = new ModelManager({
    storage, model,
    fetchImpl: async (url) => { fetched.push(url); return { ok: true, arrayBuffer: async () => b.buffer.slice(0) }; },
    estimate: async () => ({ free: 1e9, quota: 2e9, usage: 0 })
  });
  await manager.ensureInstalled();
  assert.deepEqual(fetched, ['https://example.test/b.bin']);
  assert.equal(manager.state, MODEL_STATE.READY);
  delete globalThis.caches;
});

test('cache name is stable', () => {
  assert.equal(MODEL_CACHE_NAME, 'avnivo-whisper-small-v1');
});

// ---- service worker safety ------------------------------------------------

test('service worker never evicts the model cache', async () => {
  const { readFile } = await import('node:fs/promises');
  const { fileURLToPath } = await import('node:url');
  const swPath = fileURLToPath(new URL('../../sw.js', import.meta.url));
  const source = await readFile(swPath, 'utf8');
  // The activate handler must exclude the model cache prefix from deletion.
  assert.match(source, /MODEL_CACHE_PREFIX/);
  assert.match(source, /!key\.startsWith\(MODEL_CACHE_PREFIX\)/);
  assert.equal(source.includes(MODEL_CACHE_NAME), false);
});
