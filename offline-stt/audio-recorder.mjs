/*
 * AVNIVO offline STT — AudioRecorder.
 *
 * Captures microphone audio and returns it in the exact shape Whisper needs:
 * mono, 16 kHz, Float32 in [-1, 1]. Recording uses MediaRecorder (well supported on
 * Android Chrome) and the resulting clip is decoded and resampled with the Web Audio
 * API, so no audio ever leaves the device.
 */

export class AudioError extends Error {
  constructor(reason, message) {
    super(message);
    this.name = 'AudioError';
    this.reason = reason;
  }
}

export const TARGET_SAMPLE_RATE = 16000;
const DEFAULT_MAX_MS = 60000;
const MIN_SAMPLES = 1600; // 0.1 s

function pickMimeType() {
  if (typeof MediaRecorder === 'undefined') return null;
  return ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus']
    .find((type) => MediaRecorder.isTypeSupported(type)) || null;
}

export function isRecordingSupported() {
  return typeof navigator !== 'undefined'
    && Boolean(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)
    && typeof MediaRecorder !== 'undefined'
    && typeof (globalThis.AudioContext || globalThis.webkitAudioContext) === 'function';
}

async function toMono16k(arrayBuffer, mimeType) {
  const Ctx = globalThis.AudioContext || globalThis.webkitAudioContext;
  const decodeCtx = new Ctx();
  let decoded;
  try {
    decoded = await decodeCtx.decodeAudioData(arrayBuffer.slice(0));
  } finally {
    decodeCtx.close?.();
  }
  const frames = Math.max(1, Math.ceil((decoded.duration || 0) * TARGET_SAMPLE_RATE));
  const offline = new OfflineAudioContext(1, frames, TARGET_SAMPLE_RATE);
  const source = offline.createBufferSource();
  source.buffer = decoded;
  source.connect(offline.destination);
  source.start(0);
  const rendered = await offline.startRendering();
  return rendered.getChannelData(0);
}

export class AudioRecorder {
  #maxMs;
  #stream = null;
  #recorder = null;
  #chunks = [];
  #timer = null;
  #rejectStop = null;
  #state = 'idle';

  constructor({ maxDurationMs = DEFAULT_MAX_MS } = {}) {
    this.#maxMs = maxDurationMs;
  }

  get state() {
    return this.#state;
  }

  async start() {
    if (this.#state !== 'idle') return;
    if (!isRecordingSupported()) {
      throw new AudioError('unsupported', 'Bu brauzer mikrofon orqali ovoz yozishni qo‘llamaydi.');
    }
    const mimeType = pickMimeType();
    if (!mimeType) throw new AudioError('unsupported', 'Mos audio formati topilmadi.');

    try {
      this.#stream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true }
      });
    } catch (error) {
      const denied = error && (error.name === 'NotAllowedError' || error.name === 'SecurityError');
      throw new AudioError(denied ? 'permission' : 'device',
        denied ? 'Mikrofonga ruxsat berilmagan. Brauzer sozlamalaridan ruxsat bering.' : 'Mikrofon ishga tushmadi.');
    }

    this.#chunks = [];
    this.#recorder = new MediaRecorder(this.#stream, { mimeType });
    this.#recorder.ondataavailable = (event) => { if (event.data && event.data.size) this.#chunks.push(event.data); };
    this.#state = 'recording';
    this.#recorder.start();
    this.#timer = setTimeout(() => { this.stop().catch(() => {}); }, this.#maxMs);
  }

  #releaseStream() {
    if (this.#timer) { clearTimeout(this.#timer); this.#timer = null; }
    this.#stream?.getTracks().forEach((track) => track.stop());
    this.#stream = null;
  }

  stop() {
    if (this.#state !== 'recording' || !this.#recorder) return Promise.resolve(null);
    this.#state = 'stopping';
    return new Promise((resolve, reject) => {
      this.#rejectStop = reject;
      this.#recorder.onstop = async () => {
        const mimeType = this.#recorder.mimeType || 'audio/webm';
        const blob = new Blob(this.#chunks, { type: mimeType.split(';')[0] });
        this.#recorder = null;
        this.#releaseStream();
        this.#state = 'idle';
        try {
          if (blob.size === 0) throw new AudioError('empty', 'Ovoz yozilmadi, qayta urinib ko‘ring.');
          const audio = await toMono16k(await blob.arrayBuffer(), mimeType);
          if (audio.length < MIN_SAMPLES) throw new AudioError('short', 'Ovoz juda qisqa, qayta gapiring.');
          resolve({ audio, sampleRate: TARGET_SAMPLE_RATE, durationSeconds: audio.length / TARGET_SAMPLE_RATE });
        } catch (error) {
          reject(error instanceof AudioError ? error : new AudioError('decode', 'Ovozni o‘qib bo‘lmadi.'));
        }
      };
      this.#recorder.onerror = () => {
        this.#recorder = null;
        this.#releaseStream();
        this.#state = 'idle';
        reject(new AudioError('device', 'Ovoz yozishda xato yuz berdi.'));
      };
      try {
        this.#recorder.stop();
      } catch {
        this.#releaseStream();
        this.#state = 'idle';
        reject(new AudioError('device', 'Ovoz yozishni to‘xtatib bo‘lmadi.'));
      }
    });
  }

  cancel() {
    if (this.#timer) { clearTimeout(this.#timer); this.#timer = null; }
    if (this.#recorder) {
      try { this.#recorder.stop(); } catch { /* already stopped */ }
      this.#recorder = null;
    }
    this.#releaseStream();
    this.#chunks = [];
    if (this.#rejectStop) { this.#rejectStop(new AudioError('cancelled', 'Bekor qilindi.')); this.#rejectStop = null; }
    this.#state = 'idle';
  }
}
