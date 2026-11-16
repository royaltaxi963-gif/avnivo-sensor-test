/*
 * AVNIVO offline STT — STTRouter.
 *
 * Pure decision logic: given the selected language and whether the shared model is
 * installed, decide which engine must handle the microphone. Kept free of DOM and
 * browser APIs so it can be unit-tested directly.
 *
 *   Uzbek      -> the existing custom module (never Whisper)
 *   other six  -> the shared Whisper Small model, if installed
 *   otherwise  -> unavailable, with a reason the UI can show
 */

import { getLanguage } from './language-registry.mjs';

export const ROUTE = Object.freeze({
  CUSTOM: 'custom',
  WHISPER: 'whisper',
  UNAVAILABLE: 'unavailable'
});

export function routeLanguage(code, { modelInstalled = false } = {}) {
  const entry = getLanguage(code);
  if (!entry) {
    return { route: ROUTE.UNAVAILABLE, code, reason: 'unknown_language', message: 'Bu til qo‘llab-quvvatlanmaydi.' };
  }
  if (entry.module === 'custom') {
    return { route: ROUTE.CUSTOM, code, entry };
  }
  if (!modelInstalled) {
    return {
      route: ROUTE.UNAVAILABLE,
      code,
      entry,
      reason: 'model_not_installed',
      message: 'Whisper Small modeli hali yuklanmagan. Sozlamalar → Offline rejim bo‘limidan yuklab oling.'
    };
  }
  return { route: ROUTE.WHISPER, code, entry, whisperLanguage: entry.whisperLanguage };
}

export function appendTranscript(base, text) {
  const left = (base || '').trim();
  const right = (text || '').trim();
  return [left, right].filter(Boolean).join(' ');
}

// What the microphone click should do. Kept pure so the host-page isolation rules
// (online engine, Uzbek, missing model) can be unit-tested without a DOM.
export const MIC_ACTION = Object.freeze({
  HOST: 'host',       // let the host page handle it (Chirp 2 online or the custom module)
  WHISPER: 'whisper', // this module records and transcribes with Whisper
  BLOCK: 'block'      // offline was chosen but the model is missing / language unsupported
});

export function decideMicRoute({ languageCode, modelInstalled = false, onlineEngine = false } = {}) {
  if (onlineEngine) return { action: MIC_ACTION.HOST, reason: 'online_engine' };
  const decision = routeLanguage(languageCode, { modelInstalled });
  if (decision.route === ROUTE.CUSTOM) {
    return { action: MIC_ACTION.HOST, reason: 'custom_module', decision };
  }
  if (decision.route === ROUTE.UNAVAILABLE) {
    return { action: MIC_ACTION.BLOCK, reason: decision.reason, message: decision.message, decision };
  }
  return { action: MIC_ACTION.WHISPER, reason: 'whisper', whisperLanguage: decision.whisperLanguage, decision };
}
