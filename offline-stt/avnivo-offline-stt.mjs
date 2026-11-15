/*
 * AVNIVO offline STT — browser integration.
 *
 * Adds the "Sozlamalar → Offline rejim → Tillar boshqaruvi" section, owns the shared
 * model lifecycle, and routes the microphone button:
 *
 *   - Uzbek          : left untouched; the existing custom module keeps handling it.
 *   - other six      : handled here with the shared Whisper Small model, fully offline.
 *
 * The module is defensive: if a page element is missing it degrades instead of
 * throwing, so it can never take the translator UI down.
 */

import { LANGUAGE_REGISTRY, WHISPER_MODEL_SIZE_LABEL, usesWhisper, getLanguage } from './language-registry.mjs';
import { OfflineStorage, estimateStorage } from './offline-storage.mjs';
import { ModelManager, MODEL_STATE } from './model-manager.mjs';
import { SHARED_MODEL } from './model-spec.mjs';
import { AudioRecorder, isRecordingSupported } from './audio-recorder.mjs';
import { STTEngine } from './stt-engine.mjs';
import { routeLanguage, ROUTE, appendTranscript } from './stt-router.mjs';

const storage = new OfflineStorage();
const manager = new ModelManager({ storage, model: SHARED_MODEL });
const recorder = new AudioRecorder();
let engine = null;
let installed = false;
let busy = false;
let offlineLanguage = 'uz';

const ui = {};

function el(tag, props = {}, children = []) {
  const node = document.createElement(tag);
  Object.assign(node, props);
  for (const child of [].concat(children)) node.append(child);
  return node;
}

const OFFLINE_STYLE = `
#avnivoOfflineStt{margin-top:16px;padding-top:14px;border-top:1px solid #ffffff1a;display:flex;flex-direction:column;gap:10px}
#avnivoOfflineStt .avnivo-offline-title{margin:0;font-size:15px}
#avnivoOfflineStt .avnivo-offline-note{margin:0;font-size:12px;line-height:1.55;color:#d8c3a0}
#avnivoOfflineStt .avnivo-offline-row{display:flex;align-items:center;gap:8px;flex-wrap:wrap;font-size:13px}
#avnivoOfflineStt select{flex:1;min-width:140px;padding:8px;border-radius:8px;background:#fff;color:#222}
#avnivoOfflineStt .avnivo-offline-table{width:100%;border-collapse:collapse;font-size:12px}
#avnivoOfflineStt .avnivo-offline-table th,#avnivoOfflineStt .avnivo-offline-table td{text-align:left;padding:6px 8px;border-bottom:1px solid #ffffff14}
#avnivoOfflineStt .avnivo-offline-table td.ok{color:#8fe6b0}
#avnivoOfflineStt .avnivo-offline-table td.pending{color:#f5cf85}
#avnivoOfflineStt .avnivo-offline-meta{font-size:12px;color:#cbb693}
#avnivoOfflineStt .avnivo-offline-actions{display:flex;gap:8px;flex-wrap:wrap}
#avnivoOfflineStt .avnivo-offline-btn{border:0;border-radius:12px;padding:11px 14px;font-weight:700;background:linear-gradient(135deg,#ffdc8a,#db9c38);color:#2b1b08}
#avnivoOfflineStt .avnivo-offline-btn.secondary{background:#ffffff14;color:#ffe0a0}
#avnivoOfflineStt .avnivo-offline-btn:disabled{opacity:.5;cursor:default}
#avnivoOfflineStt progress{width:100%;height:12px}
#avnivoOfflineStt .avnivo-offline-status{font-size:12px;min-height:18px;color:#f5cf85}
#avnivoOfflineStt .avnivo-offline-status[data-tone=ok]{color:#8fe6b0}
#avnivoOfflineStt .avnivo-offline-status[data-tone=error]{color:#ff9ca8}
`;

function injectStyles() {
  if (document.getElementById('avnivoOfflineSttStyle')) return;
  const style = document.createElement('style');
  style.id = 'avnivoOfflineSttStyle';
  style.textContent = OFFLINE_STYLE;
  document.head.append(style);
}

function buildUi() {
  const card = document.querySelector('#sensorSheet .sensor-card');
  if (!card || document.getElementById('avnivoOfflineStt')) return false;
  injectStyles();

  const section = el('section', { id: 'avnivoOfflineStt', className: 'avnivo-offline' });

  const heading = el('h3', { className: 'avnivo-offline-title', textContent: 'Offline rejim — Tillar boshqaruvi' });
  const note = el('p', {
    className: 'avnivo-offline-note',
    textContent: 'Oltita til (Rus, Ingliz, Hind, Bengal, Turk, Turkman) bitta umumiy Whisper Small Multilingual modelidan foydalanadi. Model bir marta yuklanadi va boshqa tillar uchun qayta yuklanmaydi. O‘zbek tili mavjud maxsus modulda qoladi.'
  });

  const langLabel = el('label', { htmlFor: 'avnivoOfflineLanguage', textContent: 'Offline ovoz tanish tili: ' });
  const select = el('select', { id: 'avnivoOfflineLanguage' });
  for (const entry of LANGUAGE_REGISTRY) {
    select.append(el('option', { value: entry.code, textContent: entry.displayName }));
  }
  const selectRow = el('div', { className: 'avnivo-offline-row' }, [langLabel, select]);

  const table = el('table', { className: 'avnivo-offline-table' });
  const thead = el('thead', {}, [el('tr', {}, [
    el('th', { textContent: 'Til' }),
    el('th', { textContent: 'Modul' }),
    el('th', { textContent: 'Holat' })
  ])]);
  const tbody = el('tbody', { id: 'avnivoOfflineRows' });
  table.append(thead, tbody);

  const sizeLine = el('div', { className: 'avnivo-offline-meta', id: 'avnivoOfflineMeta' });

  const downloadBtn = el('button', { type: 'button', id: 'avnivoOfflineDownload', className: 'avnivo-offline-btn', textContent: 'Modelni yuklab olish' });
  const removeBtn = el('button', { type: 'button', id: 'avnivoOfflineRemove', className: 'avnivo-offline-btn secondary', textContent: 'Modelni o‘chirish' });
  const buttons = el('div', { className: 'avnivo-offline-actions' }, [downloadBtn, removeBtn]);

  const progress = el('progress', { id: 'avnivoOfflineProgress', max: 100, value: 0 });
  const status = el('div', { className: 'avnivo-offline-status', id: 'avnivoOfflineStatus', role: 'status', 'aria-live': 'polite' });

  section.append(heading, note, selectRow, table, sizeLine, buttons, progress, status);
  card.append(section);

  Object.assign(ui, { section, select, tbody, meta: sizeLine, downloadBtn, removeBtn, progress, status });
  return true;
}

function setStatus(message, tone = 'info') {
  if (!ui.status) return;
  ui.status.textContent = message || '';
  ui.status.dataset.tone = tone;
}

function renderRows() {
  if (!ui.tbody) return;
  ui.tbody.replaceChildren();
  for (const entry of LANGUAGE_REGISTRY) {
    const stateText = entry.module === 'custom'
      ? 'Mavjud modul'
      : installed ? 'Tayyor' : 'Yuklanmagan';
    ui.tbody.append(el('tr', {}, [
      el('td', { textContent: entry.displayName }),
      el('td', { textContent: entry.module === 'custom' ? 'Maxsus STT' : 'Whisper Small' }),
      el('td', { className: entry.module === 'custom' ? 'ok' : (installed ? 'ok' : 'pending'), textContent: stateText })
    ]));
  }
}

async function refresh() {
  const status = await manager.status();
  installed = status.installed;
  renderRows();
  if (ui.downloadBtn) ui.downloadBtn.disabled = installed || busy;
  if (ui.removeBtn) ui.removeBtn.disabled = !installed || busy;
  if (ui.meta) {
    const estimate = await estimateStorage();
    const parts = [`Model hajmi: ${WHISPER_MODEL_SIZE_LABEL}`];
    if (estimate) parts.push(`Bo‘sh xotira: ${(estimate.free / 1e6).toFixed(0)} MB`);
    parts.push(`Saqlash: ${storage.persistent ? 'doimiy (Cache Storage)' : 'vaqtinchalik (xotira)'}`);
    ui.meta.textContent = parts.join(' · ');
  }
  if (!installed && ui.progress) ui.progress.value = 0;
}

function onProgress(update) {
  if (!ui.progress) return;
  if (update.totalBytes) {
    ui.progress.value = Math.min(100, Math.round((update.downloadedBytes / update.totalBytes) * 100));
  }
  if (update.state === MODEL_STATE.CHECKING_STORAGE) setStatus('Xotira tekshirilmoqda…');
  else if (update.state === MODEL_STATE.DOWNLOADING) {
    const done = (update.downloadedBytes / 1e6).toFixed(1);
    const total = (update.totalBytes / 1e6).toFixed(1);
    setStatus(`Yuklanmoqda: ${ui.progress.value}% (${done} / ${total} MB)`);
  } else if (update.state === MODEL_STATE.VERIFYING) setStatus('Yaxlitlik tekshirilmoqda…');
  else if (update.state === MODEL_STATE.READY) setStatus('Model tayyor.', 'ok');
  else if (update.state === MODEL_STATE.ERROR) setStatus(update.message || 'Xato yuz berdi.', 'error');
}

async function download() {
  if (busy || installed) return;
  busy = true;
  if (ui.downloadBtn) ui.downloadBtn.disabled = true;
  try {
    await manager.ensureInstalled(onProgress);
    // Prove the model actually runs before reporting "Tayyor".
    setStatus('Model ishga tushirilmoqda…');
    engine = engine || new STTEngine();
    const ok = await engine.load();
    if (!ok) throw new Error('Model yuklandi, lekin ishga tushmadi.');
    installed = true;
    setStatus('Model tayyor va ishga tushdi.', 'ok');
  } catch (error) {
    setStatus(error.message || 'Yuklab olishda xato.', 'error');
  } finally {
    busy = false;
    await refresh();
  }
}

async function remove() {
  if (busy) return;
  busy = true;
  try {
    engine?.dispose();
    engine = null;
    await manager.remove();
    installed = false;
    setStatus('Model o‘chirildi.');
  } finally {
    busy = false;
    await refresh();
  }
}

function activeField() {
  const app = document.getElementById('app');
  return app?.querySelector('.speaker.middle .message-input')
    || app?.querySelector('.speaker .message-input')
    || document.querySelector('#app .speaker.middle .message-input')
    || document.querySelector('.message-input')
    || null;
}

function writeTranscript(text) {
  const field = activeField();
  if (!field) return false;
  window.avnivoComposer?.showBottomEditor?.();
  field.value = appendTranscript(field.value, text);
  field.dispatchEvent(new Event('input', { bubbles: true }));
  field.scrollIntoView?.({ block: 'nearest' });
  return true;
}

async function runWhisper(languageCode) {
  if (busy) return;
  if (!isRecordingSupported()) {
    setStatus('Bu brauzer mikrofon orqali ovoz yozishni qo‘llamaydi.', 'error');
    return;
  }
  busy = true;
  if (ui.downloadBtn) ui.downloadBtn.disabled = true;
  try {
    engine = engine || new STTEngine();
    if (!engine.ready) {
      setStatus('Model ishga tushirilmoqda…');
      const ok = await engine.load();
      if (!ok) throw new Error('Model ishga tushmadi.');
    }
    setStatus('Ovoz yozilmoqda… Gapiring, so‘ng mikrofonni yana bosing.');
    await recorder.start();
    setStatus('Yozilmoqda…');
    // Wait for the user to press the microphone again.
    await new Promise((resolve) => { micStop = resolve; });
    micStop = null;
    const clip = await recorder.stop();
    if (!clip) return;
    setStatus('Matnga aylantirilmoqda…');
    const whisperLanguage = LANGUAGE_REGISTRY.find((entry) => entry.code === languageCode)?.whisperLanguage;
    const text = await engine.transcribe(clip.audio, whisperLanguage);
    if (!text) throw new Error('Ovozdan matn aniqlanmadi.');
    if (!writeTranscript(text)) throw new Error('Matn maydoni topilmadi.');
    setStatus('Tayyor.', 'ok');
  } catch (error) {
    recorder.cancel();
    setStatus(error.message || 'Ovozni matnga aylantirib bo‘lmadi.', 'error');
  } finally {
    busy = false;
    await refresh();
  }
}

let micStop = null;

function currentLanguageCode() {
  // Prefer this module's own language picker; fall back to the host page's online
  // language selection so the two stay aligned when the host drives the choice.
  if (ui.select && ui.select.value) return ui.select.value;
  return offlineLanguage;
}

// The host page exposes an online/offline engine switch. When the user has chosen the
// online engine (Google Chirp 2) we must not touch the microphone at all.
function hostIsOnline() {
  const select = document.getElementById('avnivoSpeechEngine');
  return Boolean(select && select.value && select.value !== 'offline');
}

function installMicRouter() {
  // Capture on the document, not the button: this runs before any element-level
  // listener the host page may already have attached to #mic, so we can take over
  // the microphone for Whisper languages without disturbing the existing handlers.
  document.addEventListener('click', (event) => {
    const mic = event.target instanceof Element ? event.target.closest('#mic') : null;
    if (!mic) return;
    if (busy && recorder.state === 'recording') {
      event.preventDefault();
      event.stopImmediatePropagation();
      micStop?.();
      return;
    }
    if (hostIsOnline()) return; // online engine (Chirp 2) owns the microphone
    const decision = routeLanguage(currentLanguageCode(), { modelInstalled: installed });
    if (decision.route === ROUTE.CUSTOM) return; // existing module keeps the microphone
    event.preventDefault();
    event.stopImmediatePropagation();
    if (decision.route === ROUTE.UNAVAILABLE) {
      setStatus(decision.message, 'error');
      return;
    }
    runWhisper(currentLanguageCode());
  }, { capture: true });
}

function bindUi() {
  if (!ui.section) return;
  ui.select.value = offlineLanguage;
  ui.select.addEventListener('change', () => {
    offlineLanguage = ui.select.value;
    const decision = routeLanguage(offlineLanguage, { modelInstalled: installed });
    if (decision.route === ROUTE.UNAVAILABLE) setStatus(decision.message, 'error');
    else if (decision.route === ROUTE.CUSTOM) setStatus('O‘zbek tili mavjud maxsus modulda ishlaydi.');
    else setStatus(`${decision.entry.displayName} tili Whisper Small orqali offline ishlaydi.`);
  });
  ui.downloadBtn.addEventListener('click', download);
  ui.removeBtn.addEventListener('click', remove);
}

async function init() {
  buildUi();
  bindUi();
  installMicRouter();
  if (ui.select) ui.select.value = offlineLanguage;
  try {
    await refresh();
  } catch {
    /* keep the UI usable even if status probing fails */
  }
  if (ui.section) setStatus('Model yuklanmagan bo‘lsa, avval uni yuklab oling.');
}

if (typeof document !== 'undefined') {
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
}

export const offlineSttApi = {
  routeLanguage,
  currentLanguageCode: () => currentLanguageCode(),
  isInstalled: () => installed,
  usesWhisper,
  getLanguage,
  setLanguage: (code) => {
    if (getLanguage(code)) {
      offlineLanguage = code;
      if (ui.select) ui.select.value = code;
    }
  },
  load: async () => {
    engine = engine || new STTEngine();
    return engine.load();
  },
  transcribe: async (audio, languageCode) => {
    const entry = getLanguage(languageCode);
    if (!entry || entry.module !== 'whisper') throw new Error('Bu til Whisper orqali ishlanmaydi.');
    engine = engine || new STTEngine();
    if (!engine.ready && !(await engine.load())) throw new Error('Model ishga tushmadi.');
    return engine.transcribe(audio, entry.whisperLanguage);
  }
};

// Stable integration surface for the host page and for a future Android WebView
// bridge. Harmless to expose: it only wraps the same logic the UI already uses.
if (typeof window !== 'undefined') {
  window.avnivoOfflineStt = offlineSttApi;
}
