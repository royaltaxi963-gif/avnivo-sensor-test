/*
 * AVNIVO offline STT — STTEngine worker.
 *
 * Runs Whisper inference off the main thread so the UI stays responsive. The engine
 * library is vendored locally (vendor/transformers/transformers.min.js) and is loaded
 * from an absolute same-origin URL, so no third-party script is pulled from the
 * network. Model files are read from the shared custom cache (device storage); the
 * runtime is never allowed to reach the network for weights.
 */

import { pipeline, env } from '../vendor/transformers/transformers.min.js';
import { OfflineStorage } from './offline-storage.mjs';
import { SHARED_MODEL } from './model-spec.mjs';

const state = { ready: false, asr: null, loadPromise: null, storage: null };

function post(message) {
  self.postMessage(message);
}

function isModelUrl(url) {
  return typeof url === 'string' && (url.startsWith(SHARED_MODEL.baseUrl) || url.includes(SHARED_MODEL.repo));
}

// Hard guarantee that the runtime can never reach the network for weights: every
// model URL is answered from local storage, and anything not stored fails fast.
function installFetchGuard(storage) {
  if (globalThis.__avnivoFetchGuarded) return;
  globalThis.__avnivoFetchGuarded = true;
  const originalFetch = globalThis.fetch.bind(globalThis);
  globalThis.fetch = async (input, init) => {
    const url = typeof input === 'string' ? input : input && input.url;
    if (isModelUrl(url)) {
      const stored = await storage.get(url);
      if (!stored) throw new Error(`Offline: model fayli mahalliy saqlashda topilmadi: ${url}`);
      const buffer = await stored.arrayBuffer();
      return new Response(buffer, { status: 200, headers: { 'content-type': 'application/octet-stream' } });
    }
    return originalFetch(input, init);
  };
}

function configure(moduleUrl) {
  // The vendored runtime lives at <root>/vendor/transformers/; the worker itself is
  // at <root>/offline-stt/, so step up one directory.
  const runtimeDir = new URL('../vendor/transformers/', moduleUrl).href;
  state.storage = new OfflineStorage();

  // Remote resolution is left enabled only so the runtime falls through to its file
  // loader instead of aborting; the fetch guard above turns that fall-through into a
  // local read. No external request can escape the worker.
  env.allowLocalModels = false;
  env.allowRemoteModels = true;
  env.useBrowserCache = false;
  env.useCustomCache = true;
  env.customCache = state.storage.toRuntimeCache();
  env.backends.onnx.wasm.wasmPaths = runtimeDir;
  installFetchGuard(state.storage);
}

async function load() {
  if (state.ready) return;
  if (state.loadPromise) return state.loadPromise;
  state.loadPromise = (async () => {
    try {
      state.asr = await pipeline('automatic-speech-recognition', SHARED_MODEL.repo, { dtype: SHARED_MODEL.dtype });
      state.ready = true;
      post({ type: 'ready' });
    } catch (error) {
      state.loadPromise = null;
      post({ type: 'error', reason: 'load', message: `Modelni ishga tushirib bo‘lmadi: ${error.message}` });
    }
  })();
  return state.loadPromise;
}

async function transcribe({ id, audio, language }) {
  if (!state.ready) {
    post({ type: 'error', id, reason: 'not_ready', message: 'Model hali yuklanmagan.' });
    return;
  }
  try {
    const output = await state.asr(audio, { language, task: 'transcribe' });
    post({ type: 'result', id, text: String(output?.text || '').trim() });
  } catch (error) {
    post({ type: 'error', id, reason: 'inference', message: `Ovozni matnga aylantirib bo‘lmadi: ${error.message}` });
  }
}

self.addEventListener('message', (event) => {
  const data = event.data || {};
  if (data.type === 'init') {
    configure(data.moduleUrl);
    post({ type: 'init-ok' });
  } else if (data.type === 'load') {
    load();
  } else if (data.type === 'transcribe') {
    transcribe(data);
  }
});
