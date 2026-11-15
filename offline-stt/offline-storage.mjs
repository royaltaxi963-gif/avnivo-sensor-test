/*
 * AVNIVO offline STT — OfflineStorage.
 *
 * Persists the shared model files on the device and exposes exactly the subset of
 * the Web Cache API (`match` / `put`) that Transformers.js requires, so the runtime
 * can read the model from local storage and never from the network.
 *
 * The browser may evict Cache Storage under pressure, so presence is always checked
 * before any language is reported as ready. When Cache Storage is unavailable (for
 * example a plain file:// document) an in-memory fallback keeps the module usable for
 * a single session, and callers are told it is not persistent.
 */

export const MODEL_CACHE_NAME = 'avnivo-whisper-small-v1';

function hasCacheStorage() {
  return typeof caches !== 'undefined' && typeof caches.open === 'function';
}

export class OfflineStorage {
  #memory = new Map();
  #mode = hasCacheStorage() ? 'cache' : 'memory';

  get mode() {
    return this.#mode;
  }

  get persistent() {
    return this.#mode === 'cache';
  }

  async #cache() {
    if (this.#mode !== 'cache') return null;
    try {
      return await caches.open(MODEL_CACHE_NAME);
    } catch {
      this.#mode = 'memory';
      return null;
    }
  }

  async has(url) {
    const cache = await this.#cache();
    if (!cache) return this.#memory.has(url);
    return Boolean(await cache.match(url));
  }

  async get(url) {
    const cache = await this.#cache();
    if (!cache) return this.#memory.get(url) || null;
    const response = await cache.match(url);
    return response || null;
  }

  async put(url, blob) {
    const cache = await this.#cache();
    if (!cache) {
      this.#memory.set(url, blob);
      return;
    }
    await cache.put(url, new Response(blob, { headers: { 'content-type': 'application/octet-stream' } }));
  }

  async delete(url) {
    const cache = await this.#cache();
    if (!cache) {
      this.#memory.delete(url);
      return;
    }
    await cache.delete(url);
  }

  async clear() {
    this.#memory.clear();
    if (this.#mode === 'cache') {
      try {
        await caches.delete(MODEL_CACHE_NAME);
      } catch {
        /* cache already gone */
      }
    }
  }

  // Adapter handed to Transformers.js via env.customCache.
  toRuntimeCache() {
    return {
      match: (request) => this.get(typeof request === 'string' ? request : request.url),
      put: async (request, response) => {
        const url = typeof request === 'string' ? request : request.url;
        const blob = await response.blob();
        await this.put(url, blob);
      }
    };
  }
}

export async function estimateStorage() {
  if (typeof navigator === 'undefined' || !navigator.storage || typeof navigator.storage.estimate !== 'function') {
    return null;
  }
  try {
    const { usage = 0, quota = 0 } = await navigator.storage.estimate();
    return { usage, quota, free: Math.max(0, quota - usage) };
  } catch {
    return null;
  }
}
