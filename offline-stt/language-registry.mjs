/*
 * AVNIVO offline STT — language registry.
 *
 * Single source of truth shared by the browser settings UI, the microphone router
 * and the tests. Uzbek is deliberately mapped to the existing custom module and is
 * never handled by Whisper here.
 */

import { SHARED_MODEL, SHARED_MODEL_BYTES } from './model-spec.mjs';

export const LANGUAGE_REGISTRY = Object.freeze([
  Object.freeze({
    code: 'uz',
    languageTag: 'uz-UZ',
    whisperLanguage: null,
    displayName: "O‘zbek",
    module: 'custom',
    moduleLabel: 'Maxsus STT moduli',
    reason: "Mavjud maxsus offline modul ishlatiladi; Whisper Small bu tilga ulanmaydi."
  }),
  Object.freeze({ code: 'ru', languageTag: 'ru-RU', whisperLanguage: 'russian', displayName: 'Rus', module: 'whisper' }),
  Object.freeze({ code: 'en', languageTag: 'en-US', whisperLanguage: 'english', displayName: 'Ingliz', module: 'whisper' }),
  Object.freeze({ code: 'hi', languageTag: 'hi-IN', whisperLanguage: 'hindi', displayName: 'Hind', module: 'whisper' }),
  Object.freeze({ code: 'bn', languageTag: 'bn-BD', whisperLanguage: 'bengali', displayName: 'Bengal', module: 'whisper' }),
  Object.freeze({ code: 'tr', languageTag: 'tr-TR', whisperLanguage: 'turkish', displayName: 'Turk', module: 'whisper' }),
  Object.freeze({ code: 'tk', languageTag: 'tk-TM', whisperLanguage: 'turkmen', displayName: 'Turkman', module: 'whisper' })
]);

// Every Whisper language above shares this one model. Selecting another language must
// never trigger a second download.
export const WHISPER_MODEL_ID = SHARED_MODEL.id;
export const WHISPER_MODEL_BYTES = SHARED_MODEL_BYTES;
export const WHISPER_MODEL_SIZE_LABEL = `${(SHARED_MODEL_BYTES / 1e6).toFixed(1)} MB`;

const BY_CODE = new Map(LANGUAGE_REGISTRY.map((entry) => [entry.code, entry]));

export function getLanguage(code) {
  return BY_CODE.get(code) || null;
}

export function whisperLanguages() {
  return LANGUAGE_REGISTRY.filter((entry) => entry.module === 'whisper');
}

export function usesWhisper(code) {
  const entry = BY_CODE.get(code);
  return Boolean(entry && entry.module === 'whisper');
}

export function languageCodes() {
  return LANGUAGE_REGISTRY.map((entry) => entry.code);
}

export function assertRegistryConsistent() {
  if (LANGUAGE_REGISTRY.length !== 7) throw new Error('Registry must hold exactly seven languages');
  const seen = new Set();
  for (const entry of LANGUAGE_REGISTRY) {
    if (seen.has(entry.code)) throw new Error(`Duplicate language code: ${entry.code}`);
    seen.add(entry.code);
    if (entry.module === 'whisper' && !entry.whisperLanguage) {
      throw new Error(`Whisper language missing for ${entry.code}`);
    }
    if (entry.module === 'custom' && entry.whisperLanguage !== null) {
      throw new Error(`Custom module must not carry a Whisper language: ${entry.code}`);
    }
  }
  return true;
}
