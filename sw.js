/*
 * AVNIVO Translator — service worker.
 *
 * Caches the application shell and the vendored offline STT runtime so the page can
 * be reopened and used with the network switched off. Model weights are NOT handled
 * here: they live in the dedicated model Cache Storage and are managed by
 * ModelManager. This worker only serves same-origin static assets.
 */

const VERSION = 'avnivo-v11.49';
const SHELL_CACHE = `${VERSION}-shell`;
const RUNTIME_CACHE = `${VERSION}-runtime`;

// The model lives in its own Cache Storage (see OfflineStorage.MODEL_CACHE_NAME).
// It must never be listed here and must never be evicted by this worker.
const MODEL_CACHE_PREFIX = 'avnivo-whisper-small-';
const OWNED_CACHES = [SHELL_CACHE, RUNTIME_CACHE];

const SHELL_ASSETS = [
  './',
  './index.html',
  './avnivo-v11-47-groq-sdk-exact-code-test.html',
  './manifest.webmanifest',
  './offline-module-manager.js',
  './offline-stt/avnivo-offline-stt.mjs',
  './offline-stt/language-registry.mjs',
  './offline-stt/model-spec.mjs',
  './offline-stt/offline-storage.mjs',
  './offline-stt/model-manager.mjs',
  './offline-stt/audio-recorder.mjs',
  './offline-stt/stt-engine.mjs',
  './offline-stt/stt-engine.worker.mjs',
  './offline-stt/stt-router.mjs',
  './offline-stt/sha256.mjs'
];

const RUNTIME_ASSETS = [
  './vendor/transformers/transformers.min.js',
  './vendor/transformers/ort-wasm-simd-threaded.jsep.mjs',
  './vendor/transformers/ort-wasm-simd-threaded.jsep.wasm'
];

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    const shell = await caches.open(SHELL_CACHE);
    await shell.addAll(SHELL_ASSETS);
    const runtime = await caches.open(RUNTIME_CACHE);
    await runtime.addAll(RUNTIME_ASSETS);
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys
      .filter((key) => key.startsWith('avnivo-')
        && !key.startsWith(MODEL_CACHE_PREFIX)
        && !OWNED_CACHES.includes(key))
      .map((key) => caches.delete(key)));
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return; // never touch cross-origin model traffic

  event.respondWith((async () => {
    const cached = await caches.match(request);
    if (cached) return cached;
    try {
      const response = await fetch(request);
      if (response && response.ok && response.type === 'basic') {
        const cache = await caches.open(SHELL_CACHE);
        cache.put(request, response.clone());
      }
      return response;
    } catch (error) {
      const fallback = await caches.match('./index.html');
      if (fallback && request.mode === 'navigate') return fallback;
      throw error;
    }
  })());
});
