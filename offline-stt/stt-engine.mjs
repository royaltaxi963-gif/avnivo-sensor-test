/*
 * AVNIVO offline STT — STTEngine.
 *
 * Main-thread facade over the inference worker. It owns the worker, forwards requests
 * with ids, and enforces a bounded inference timeout so a stalled model can never
 * freeze the translator UI.
 */

const DEFAULT_TIMEOUT_MS = 120000;

export class STTEngine {
  #worker = null;
  #ready = false;
  #readyWaiters = [];
  #pending = new Map();
  #nextId = 1;
  #timeoutMs;

  constructor({ timeoutMs = DEFAULT_TIMEOUT_MS } = {}) {
    this.#timeoutMs = timeoutMs;
  }

  get ready() {
    return this.#ready;
  }

  #ensureWorker() {
    if (this.#worker) return this.#worker;
    const moduleUrl = new URL('./stt-engine.worker.mjs', import.meta.url).href;
    const worker = new Worker(moduleUrl, { type: 'module' });
    worker.addEventListener('message', (event) => this.#onMessage(event.data));
    worker.addEventListener('error', (event) => this.#onFatal(event.message || 'worker error'));
    worker.postMessage({ type: 'init', moduleUrl });
    this.#worker = worker;
    return worker;
  }

  #onFatal(message) {
    this.#ready = false;
    for (const [, entry] of this.#pending) entry.reject(new Error(`STT dvigateli xatosi: ${message}`));
    this.#pending.clear();
  }

  #onMessage(data) {
    if (data.type === 'ready') {
      this.#ready = true;
      this.#readyWaiters.splice(0).forEach((resolve) => resolve(true));
    } else if (data.type === 'error' && !data.id) {
      this.#readyWaiters.splice(0).forEach((resolve) => resolve(false));
      this.#onFatal(data.message);
    } else if (data.type === 'result' || data.type === 'error') {
      const entry = this.#pending.get(data.id);
      if (!entry) return;
      this.#pending.delete(data.id);
      clearTimeout(entry.timer);
      if (data.type === 'result') entry.resolve(data.text);
      else entry.reject(new Error(data.message));
    }
  }

  load() {
    const worker = this.#ensureWorker();
    if (this.#ready) return Promise.resolve(true);
    worker.postMessage({ type: 'load' });
    return new Promise((resolve) => this.#readyWaiters.push(resolve));
  }

  async transcribe(audio, language) {
    const worker = this.#ensureWorker();
    if (!this.#ready) await this.load();
    const id = this.#nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.#pending.delete(id);
        reject(new Error('Ovozni matnga aylantirish vaqti tugadi.'));
      }, this.#timeoutMs);
      this.#pending.set(id, { resolve, reject, timer });
      // Plain Array copies cleanly across the worker boundary.
      worker.postMessage({ type: 'transcribe', id, audio: Float32Array.from(audio), language });
    });
  }

  dispose() {
    if (this.#worker) {
      this.#worker.terminate();
      this.#worker = null;
    }
    this.#ready = false;
  }
}
