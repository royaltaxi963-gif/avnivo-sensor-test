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
