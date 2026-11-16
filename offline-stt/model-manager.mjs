/*
 * AVNIVO offline STT — ModelManager.
 *
 * Owns the lifecycle of the shared Whisper Small model on the device:
 *   not_installed -> checking_storage -> downloading -> verifying -> ready
 * with an `error` state that carries a machine-readable reason.
 *
 * Guarantees:
 *   - Only the exact files in the model allow-list are ever fetched.
 *   - Every downloaded byte is hashed while it streams and compared with the
 *     published sha256 before the file is accepted.
 *   - Files already stored are skipped, so an interrupted download resumes at file
 *     granularity and a second language never re-downloads anything.
 *   - Free space is checked against the model size before downloading starts.
 */

import { createSha256 } from './sha256.mjs';
import { estimateStorage } from './offline-storage.mjs';

export const MODEL_STATE = Object.freeze({
  NOT_INSTALLED: 'not_installed',
  CHECKING_STORAGE: 'checking_storage',
  DOWNLOADING: 'downloading',
  VERIFYING: 'verifying',
  READY: 'ready',
  ERROR: 'error'
});

export const STORAGE_SAFETY_MARGIN = 1.25;

export class ModelManager {
  #storage;
  #model;
  #fetch;
  #estimate;
  #state = MODEL_STATE.NOT_INSTALLED;
  #reason = null;

  constructor({ storage, model, fetchImpl, estimate = estimateStorage }) {
    if (!storage) throw new Error('storage is required');
    if (!model || !Array.isArray(model.files)) throw new Error('model with files is required');
    this.#storage = storage;
    this.#model = model;
    this.#fetch = fetchImpl || ((...args) => fetch(...args));
    this.#estimate = estimate;
  }

  get model() {
    return this.#model;
  }

  get totalBytes() {
    return this.#model.files.reduce((sum, file) => sum + file.bytes, 0);
  }

  fileUrl(path) {
    return this.#model.baseUrl + path;
  }

  get state() {
    return this.#state;
  }

  get reason() {
    return this.#reason;
  }

  async presentFiles() {
    const present = {};
    for (const file of this.#model.files) {
      present[file.path] = await this.#storage.has(this.fileUrl(file.path));
    }
    return present;
  }

  async isInstalled() {
    const present = await this.presentFiles();
    return this.#model.files.every((file) => present[file.path]);
  }

  async status() {
    const present = await this.presentFiles();
    const downloadedBytes = this.#model.files
      .filter((file) => present[file.path])
      .reduce((sum, file) => sum + file.bytes, 0);
    const installed = this.#model.files.every((file) => present[file.path]);
    return {
      state: installed ? MODEL_STATE.READY : this.#state === MODEL_STATE.ERROR ? MODEL_STATE.ERROR : MODEL_STATE.NOT_INSTALLED,
      reason: this.#reason,
      present,
      installed,
      totalBytes: this.totalBytes,
      downloadedBytes
    };
  }

  async #checkStorage() {
    const estimate = await this.#estimate();
    if (!estimate) return { checked: false };
    const required = Math.ceil(this.totalBytes * STORAGE_SAFETY_MARGIN);
    if (estimate.free < required) {
      const freeMb = (estimate.free / 1e6).toFixed(0);
      const needMb = (required / 1e6).toFixed(0);
      throw new ModelError('storage', `Xotira yetarli emas: ${freeMb} MB bo‘sh, ${needMb} MB kerak.`);
    }
    return { checked: true, free: estimate.free, required };
  }

  async #downloadFile(file, onProgress) {
    const url = this.fileUrl(file.path);
    let response;
    try {
      response = await this.#fetch(url);
    } catch (error) {
      throw new ModelError('network', `Ulanish uzildi: ${file.path}. Internetni tekshirib qayta urinib ko‘ring.`);
    }
    if (!response.ok) {
      throw new ModelError('network', `Yuklab olishda xato (${response.status}): ${file.path}`);
    }
    const reader = response.body?.getReader?.();
    const hasher = createSha256();
    const chunks = [];
    let received = 0;
    if (reader) {
      while (true) {
        let step;
        try {
          step = await reader.read();
        } catch {
          throw new ModelError('network', `Yuklash uzildi: ${file.path}. Davom ettirish uchun qayta urinib ko‘ring.`);
        }
        if (step.done) break;
        const value = step.value;
        received += value.length;
        hasher.update(value);
        chunks.push(value);
        onProgress(received);
      }
    } else {
      const buffer = new Uint8Array(await response.arrayBuffer());
      received = buffer.length;
      hasher.update(buffer);
      chunks.push(buffer);
      onProgress(received);
    }
    const digest = hasher.digestHex();
    if (received !== file.bytes) {
      throw new ModelError('integrity', `Fayl hajmi mos emas: ${file.path} (${received} != ${file.bytes})`);
    }
    if (digest !== file.sha256) {
      throw new ModelError('integrity', `Fayl yaxlitligi buzilgan (sha256 mos emas): ${file.path}`);
    }
    await this.#storage.put(url, new Blob(chunks));
    return received;
  }

  async ensureInstalled(onProgress = () => {}) {
    this.#state = MODEL_STATE.CHECKING_STORAGE;
    this.#reason = null;
    try {
      await this.#checkStorage();

      const present = await this.presentFiles();
      const missing = this.#model.files.filter((file) => !present[file.path]);
      const alreadyStored = this.totalBytes - missing.reduce((sum, file) => sum + file.bytes, 0);
      let downloaded = alreadyStored;

      if (missing.length > 0) {
        this.#state = MODEL_STATE.DOWNLOADING;
        for (const file of missing) {
          onProgress({ state: this.#state, path: file.path, downloadedBytes: downloaded, totalBytes: this.totalBytes });
          await this.#downloadFile(file, (received) => {
            downloaded += received - (file.__lastReported || 0);
            file.__lastReported = received;
            onProgress({ state: this.#state, path: file.path, downloadedBytes: downloaded, totalBytes: this.totalBytes });
          });
          delete file.__lastReported;
        }
      }

      this.#state = MODEL_STATE.VERIFYING;
      onProgress({ state: this.#state, downloadedBytes: this.totalBytes, totalBytes: this.totalBytes });
      const stillPresent = await this.presentFiles();
      const missingAfter = this.#model.files.filter((file) => !stillPresent[file.path]);
      if (missingAfter.length > 0) {
        throw new ModelError('integrity', 'Ba’zi fayllar saqlanmadi. Qayta urinib ko‘ring.');
      }

      this.#state = MODEL_STATE.READY;
      onProgress({ state: this.#state, downloadedBytes: this.totalBytes, totalBytes: this.totalBytes });
      return { state: this.#state, downloadedBytes: this.totalBytes, totalBytes: this.totalBytes };
    } catch (error) {
      this.#state = MODEL_STATE.ERROR;
      this.#reason = error instanceof ModelError ? error.reason : 'unknown';
      onProgress({ state: this.#state, reason: this.#reason, message: error.message });
      throw error;
    }
  }

  async remove() {
    await this.#storage.clear();
    this.#state = MODEL_STATE.NOT_INSTALLED;
    this.#reason = null;
  }
}

export class ModelError extends Error {
  constructor(reason, message) {
    super(message);
    this.name = 'ModelError';
    this.reason = reason;
  }
}
