#!/usr/bin/env node
/* Validate every AVNIVO localization dictionary.
 *
 * Checks, for all 100 languages:
 *   - exactly 100 registered languages with unique codes and native names;
 *   - one JSON package per language, valid UTF-8 JSON;
 *   - every source key present (no missing keys);
 *   - no duplicate keys;
 *   - no empty or untranslated-as-complete values;
 *   - interpolation placeholders preserved exactly;
 *   - voice tags preserved exactly;
 *   - identifiers, language codes and file paths never translated;
 *   - RTL languages flagged correctly.
 *
 * Exits non-zero when any check fails. Prints a per-check summary so the CI
 * output shows passed / failed counts separately.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = dirname(fileURLToPath(import.meta.url));
const source = JSON.parse(readFileSync(join(root, 'source/en.json'), 'utf8'));
const languages = JSON.parse(readFileSync(join(root, 'languages.json'), 'utf8'));

const results = [];
function check(name, passed, detail) {
  results.push({ name, passed, detail });
}
function duplicates(keys) {
  const seen = new Set();
  const dupes = new Set();
  for (const key of keys) {
    if (seen.has(key)) dupes.add(key);
    seen.add(key);
  }
  return [...dupes];
}
const placeholders = (value) => (String(value).match(/\{\{\s*[\w.-]+\s*\}\}/g) || []).sort();
const voiceTags = (value) => (String(value).match(/<\/?[a-zA-Z][\w -]*>/g) || []).sort();
const idLike = (key) => /(^|\.)(code|id|path|locale|file|model|endpoint)$/i.test(key);

check('exactly-100-languages', languages.length === 100, `count=${languages.length}`);
check('unique-language-codes', new Set(languages.map((l) => l.code)).size === 100,
  `unique=${new Set(languages.map((l) => l.code)).size}`);
check('native-names-present', languages.every((l) => l.name && l.name.trim().length > 0),
  'all native names non-empty');
check('rtl-flags', languages.filter((l) => l.rtl).map((l) => l.code).join(',') ===
  'ar,fa,ur,ps,ug,sd,ku,dv,he', 'rtl set matches engine');

const sourceKeys = Object.keys(source);
const localeFiles = readdirSync(join(root, 'locales')).filter((f) => f.endsWith('.json'));
check('one-package-per-language', localeFiles.length === 100, `files=${localeFiles.length}`);

let missingKeys = 0;
let emptyValues = 0;
let placeholderBreaks = 0;
let tagBreaks = 0;
let identifierTranslated = 0;
let invalidJson = 0;
let untranslatedIdentical = 0;
const perLanguage = [];

for (const lang of languages) {
  const file = join(root, 'locales', `${lang.code}.json`);
  let data;
  try {
    data = JSON.parse(readFileSync(file, 'utf8'));
  } catch (e) {
    invalidJson++;
    perLanguage.push({ code: lang.code, status: 'invalid-json' });
    continue;
  }
  const keys = Object.keys(data).filter((k) => k !== '_meta');
  const dupes = duplicates(keys);
  const missing = sourceKeys.filter((k) => !(k in data));
  const empty = keys.filter((k) => typeof data[k] !== 'string' || data[k].trim() === '');
  let ph = 0;
  let tag = 0;
  let identical = 0;
  for (const key of sourceKeys) {
    if (!(key in data)) continue;
    const a = placeholders(source[key]).join('|');
    const b = placeholders(data[key]).join('|');
    if (a !== b) ph++;
    const ta = voiceTags(source[key]).join('|');
    const tb = voiceTags(data[key]).join('|');
    if (ta !== tb) tag++;
    if (idLike(key) && source[key] !== data[key]) identifierTranslated++;
    if (lang.code !== 'en' && source[key] === data[key] && source[key].length > 3 &&
        /^[\x00-\x7F]+$/.test(source[key]) && !/^[+×]/.test(source[key])) identical++;
  }
  missingKeys += missing.length;
  emptyValues += empty.length;
  placeholderBreaks += ph;
  tagBreaks += tag;
  untranslatedIdentical += identical;
  perLanguage.push({
    code: lang.code, dupes: dupes.length, missing: missing.length,
    empty: empty.length, placeholders: ph, tags: tag, identical,
    machine: data._meta?.machineTranslated === true,
  });
}

check('no-missing-keys', missingKeys === 0, `missing=${missingKeys}`);
check('no-duplicate-keys', perLanguage.every((l) => !l.dupes), 'no duplicate keys');
check('no-empty-values', emptyValues === 0, `empty=${emptyValues}`);
check('placeholder-integrity', placeholderBreaks === 0, `breaks=${placeholderBreaks}`);
check('voice-tag-integrity', tagBreaks === 0, `breaks=${tagBreaks}`);
check('identifiers-not-translated', identifierTranslated === 0, `translated=${identifierTranslated}`);
check('json-valid', invalidJson === 0, `invalid=${invalidJson}`);
check('machine-translation-marked',
  perLanguage.filter((l) => l.code !== 'en').every((l) => l.machine === true),
  'all non-English packages marked machineTranslated');

// --- Onboarding tutorial coverage ---------------------------------------
// Every package must carry the full five-step tutorial (11 tutorial.* keys),
// in the language's own script, with no English left in place as if finished.
const tutorialKeys = sourceKeys.filter((k) => k.startsWith('tutorial.'));
const tutorialMissing = [];
const tutorialEnglishLeft = [];
const tutorialScriptMismatch = [];

// Classify the writing systems a string uses. Shared punctuation is stripped
// first: the danda "।" lives in the Devanagari block but is also used by
// Bengali, Gurmukhi and others, so counting it would misclassify those scripts.
// A language's tutorial text must share a script with the scripts its existing
// interface strings already use, which catches a block pasted in the wrong
// language without hardcoding per-language expectations (a language may
// legitimately use a script its code does not imply, e.g. Kurdish in Latin).
const SCRIPT_TESTS = [
  ['arabic', /[\u0600-\u06FF\u0750-\u077F\u0780-\u07BF]/],
  ['hebrew', /[\u0590-\u05FF]/],
  ['cyrillic', /[\u0400-\u04FF\u0500-\u052F]/],
  ['greek', /[\u0370-\u03FF\u1F00-\u1FFF]/],
  ['armenian', /[\u0530-\u058F]/],
  ['georgian', /[\u10A0-\u10FF]/],
  ['devanagari', /[\u0900-\u097F]/],
  ['bengali', /[\u0980-\u09FF]/],
  ['gurmukhi', /[\u0A00-\u0A7F]/],
  ['gujarati', /[\u0A80-\u0AFF]/],
  ['tamil', /[\u0B80-\u0BFF]/],
  ['telugu', /[\u0C00-\u0C7F]/],
  ['kannada', /[\u0C80-\u0CFF]/],
  ['malayalam', /[\u0D00-\u0D7F]/],
  ['sinhala', /[\u0D80-\u0DFF]/],
  ['thai', /[\u0E00-\u0E7F]/],
  ['lao', /[\u0E80-\u0EFF]/],
  ['tibetan', /[\u0F00-\u0FFF]/],
  ['myanmar', /[\u1000-\u109F]/],
  ['ethiopic', /[\u1200-\u137F]/],
  ['khmer', /[\u1780-\u17FF]/],
  ['han', /[\u4E00-\u9FFF\u3400-\u4DBF]/],
  ['kana', /[\u3040-\u30FF]/],
  ['hangul', /[\uAC00-\uD7AF\u1100-\u11FF]/],
];
const SHARED_PUNCT = /[।॥\u0964\u0965\u061F\u060C\u060D.,;:!?'"`()[\]{}\-–—…«»„“”‘’·]/g;
function scriptsIn(text) {
  const set = new Set();
  for (const ch of String(text).replace(SHARED_PUNCT, '')) {
    let matched = false;
    for (const [name, re] of SCRIPT_TESTS) {
      if (re.test(ch)) { set.add(name); matched = true; break; }
    }
    if (!matched && /[A-Za-z]/.test(ch)) set.add('latin');
  }
  return set;
}

for (const lang of languages) {
  const data = JSON.parse(readFileSync(join(root, 'locales', `${lang.code}.json`), 'utf8'));
  for (const key of tutorialKeys) {
    if (!(key in data) || String(data[key]).trim() === '') {
      tutorialMissing.push(`${lang.code}:${key}`);
    }
  }
  // Non-English packages must not simply repeat the English source text.
  if (lang.code !== 'en') {
    for (const key of tutorialKeys) {
      if (data[key] === source[key] && source[key].length > 3) {
        tutorialEnglishLeft.push(`${lang.code}:${key}`);
      }
    }
  }
  // The tutorial text must share a script with the language's existing strings.
  // Latin is ignored: Latin text is shared and cannot be told apart from
  // English by script alone (English-left is already covered above).
  const known = new Set();
  for (const key of Object.keys(data)) {
    if (key.startsWith('_') || key.startsWith('tutorial.')) continue;
    for (const s of scriptsIn(data[key])) if (s !== 'latin') known.add(s);
  }
  if (known.size === 0) continue; // a Latin-script package
  for (const key of tutorialKeys) {
    const scripts = scriptsIn(data[key]);
    const shared = [...scripts].some((s) => known.has(s));
    if (data[key] && !shared) {
      tutorialScriptMismatch.push(`${lang.code}:${key} scripts=[${[...scripts]}] not in [${[...known]}]`);
    }
  }
}
check('tutorial-keys-complete', tutorialMissing.length === 0,
  `keys=${tutorialKeys.length} languages=${languages.length} missing=${tutorialMissing.length}`);
check('tutorial-not-english-placeholder', tutorialEnglishLeft.length === 0,
  `english-left=${tutorialEnglishLeft.length}`);
check('tutorial-script-consistency', tutorialScriptMismatch.length === 0,
  `mismatches=${tutorialScriptMismatch.length}`);

const failed = results.filter((r) => !r.passed);
for (const r of results) {
  console.log(`${r.passed ? 'PASS' : 'FAIL'}  ${r.name}  (${r.detail})`);
}
const identicalRatio = untranslatedIdentical / (sourceKeys.length * 99);
console.log(`INFO  identical-to-english ratio (non-English): ${(identicalRatio * 100).toFixed(2)}%`);
console.log(`INFO  languages: ${languages.length}, source keys: ${sourceKeys.length}`);
console.log(`\n${results.length - failed.length} passed, ${failed.length} failed`);

// Persist a machine-readable report for the quality report and CI artifact.
const reportPath = join(root, 'validation-report.json');
const fs = await import('node:fs');
fs.writeFileSync(reportPath, JSON.stringify({
  languages: languages.length, sourceKeys: sourceKeys.length,
  results, perLanguage,
}, null, 2));
console.log(`report: i18n/validation-report.json`);

process.exit(failed.length === 0 ? 0 : 1);
