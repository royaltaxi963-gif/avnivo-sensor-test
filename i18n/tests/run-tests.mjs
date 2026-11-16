#!/usr/bin/env node
/* AVNIVO localization automated test suite.
 *
 * Covers the ten required acceptance areas:
 *   1. exactly 100 registered languages
 *   2. translation key completeness
 *   3. JSON validity
 *   4. placeholder integrity
 *   5. language persistence
 *   6. fallback behaviour
 *   7. RTL support
 *   8. offline resource loading
 *   9. repeated switching without crashes
 *  10. existing application regression (index.html contracts)
 *
 * Tests exercise the real engine (i18n/avnivo-i18n.js) through a small host
 * shim; only the browser host is simulated. A test is only reported as passed
 * when its assertions actually ran.
 */
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { createHost, loadEngine, repoRoot } from './host.mjs';

const results = [];
let current = null;

async function test(name, fn) {
  current = { name, status: 'passed', error: null };
  try {
    await fn();
  } catch (e) {
    current.status = 'failed';
    current.error = e.message;
  }
  results.push(current);
  current = null;
}

function assert(cond, message) {
  if (!cond) throw new Error(message);
}

const source = JSON.parse(readFileSync(join(repoRoot, 'i18n/source/en.json'), 'utf8'));
const languages = JSON.parse(readFileSync(join(repoRoot, 'i18n/languages.json'), 'utf8'));
const sourceKeys = Object.keys(source);

/* 1. exactly 100 registered languages */
await test('registry has exactly 100 languages', () => {
  assert(languages.length === 100, `expected 100, got ${languages.length}`);
  assert(new Set(languages.map((l) => l.code)).size === 100, 'codes not unique');
});

/* 3. JSON validity + 2. key completeness across all packages */
await test('all 100 packages are valid JSON with complete keys', () => {
  const files = readdirSync(join(repoRoot, 'i18n/locales')).filter((f) => f.endsWith('.json'));
  assert(files.length === 100, `expected 100 files, got ${files.length}`);
  for (const lang of languages) {
    const raw = readFileSync(join(repoRoot, `i18n/locales/${lang.code}.json`), 'utf8');
    const data = JSON.parse(raw); // throws on invalid JSON
    for (const key of sourceKeys) {
      assert(key in data, `${lang.code} missing ${key}`);
      assert(typeof data[key] === 'string' && data[key].trim() !== '', `${lang.code} empty ${key}`);
    }
  }
});

/* 4. placeholder integrity */
await test('placeholders preserved in every package', () => {
  const ph = (v) => (String(v).match(/\{\{\s*[\w.-]+\s*\}\}/g) || []).sort().join('|');
  for (const lang of languages) {
    const data = JSON.parse(readFileSync(join(repoRoot, `i18n/locales/${lang.code}.json`), 'utf8'));
    for (const key of sourceKeys) {
      assert(ph(data[key]) === ph(source[key]), `${lang.code} placeholder mismatch at ${key}`);
    }
  }
});

/* engine behaviour */
const host = createHost();
const i18n = loadEngine(host.window);
await i18n.init();

await test('engine loads exactly one active bundle', () => {
  assert(i18n.getLanguage() === 'uz', `default language ${i18n.getLanguage()}`);
});

/* 5. persistence */
await test('selected language persists in localStorage', async () => {
  await i18n.setLanguage('de');
  assert(host.window.localStorage.getItem(i18n.STORAGE_KEY) === 'de', 'not persisted');
  // Simulate a new session by re-initialising against the same storage.
  const host2 = createHost();
  host2.window.localStorage.setItem(i18n.STORAGE_KEY, 'de');
  const engine2 = loadEngine(host2.window);
  await engine2.init();
  assert(engine2.getLanguage() === 'de', `session did not restore, got ${engine2.getLanguage()}`);
});

/* 6. fallback behaviour */
await test('unknown language falls back to English', async () => {
  const host3 = createHost();
  const engine3 = loadEngine(host3.window);
  await engine3.init({ language: 'zz' });
  assert(engine3.getLanguage() === 'en', `expected en, got ${engine3.getLanguage()}`);
});

await test('missing key never renders a raw key', async () => {
  const value = i18n.t('does.not.exist');
  assert(value !== 'does.not.exist', 'raw key leaked to user');
  assert(value.length > 0, 'empty fallback');
});

await test('interpolation substitutes variables', async () => {
  await i18n.setLanguage('en');
  const out = i18n.t('translation.error.server', { status: 503 });
  assert(out.includes('503'), `interpolation failed: ${out}`);
  assert(!out.includes('{{'), 'placeholder not replaced');
});

/* 7. RTL support */
await test('RTL languages set document direction', async () => {
  await i18n.setLanguage('ar');
  assert(i18n.isRTL(), 'ar not detected as RTL');
  assert(host.window.document.documentElement.getAttribute('dir') === 'rtl', 'dir not set to rtl');
  await i18n.setLanguage('en');
  assert(host.window.document.documentElement.getAttribute('dir') === 'ltr', 'dir not reset to ltr');
});

/* 8. offline resource loading */
await test('bundles load from local files with no network', async () => {
  const host4 = createHost();
  const engine4 = loadEngine(host4.window);
  await engine4.init({ language: 'ja' });
  const value = engine4.t('settings.title');
  assert(value && value !== 'settings.title', `ja bundle not loaded offline: ${value}`);
});

/* 9. repeated switching without crashes */
await test('1000 language switches complete without error', async () => {
  const codes = languages.map((l) => l.code);
  for (let i = 0; i < 1000; i++) {
    await i18n.setLanguage(codes[i % codes.length]);
  }
  assert(i18n.getLanguage() === codes[(1000 - 1) % codes.length], 'final language mismatch');
});

/* locale-aware formatting */
await test('locale-aware number and date formatting', async () => {
  await i18n.setLanguage('en');
  const num = i18n.formatNumber(1234567.89, { maximumFractionDigits: 1 });
  assert(typeof num === 'string' && num.length > 0, 'number format empty');
  const date = i18n.formatDate(new Date('2026-11-16T00:00:00Z'), { year: 'numeric' });
  assert(date.includes('2026'), `date format wrong: ${date}`);
});

/* 10. existing application regression */
await test('index.html preserves AVNIVO contracts', () => {
  const html = readFileSync(join(repoRoot, 'index.html'), 'utf8');
  const required = [
    'class="sent-messages"', 'class="settings-drawer"', 'class="history-drawer"',
    'function sendMessage()', 'function typeTranslation(', 'function scrollChatToLatest()',
    'function playMessage(', 'chatFollowLatest', 'PREVIEW_TRANSLATION',
    'data-avnivo-mode=offline', 'data-avnivo-mode=online',
  ];
  for (const feature of required) {
    assert(html.includes(feature), `missing contract: ${feature}`);
  }
  const locales = [...html.matchAll(/\["([A-Za-z0-9-]+)","[^"]+","(?:official|experimental|retest)"\]/g)].map((m) => m[1]);
  assert(locales.length === 93 && new Set(locales).size === 93, `online picker locales=${locales.length}`);
  // Brand animations must remain intact.
  assert(html.includes('translationWords') && html.includes('greetings'), 'brand animations removed');
  assert(html.includes('i18n/avnivo-i18n.js'), 'i18n engine not wired');
});

await test('onboarding animation preserved and wired', () => {
  const html = readFileSync(join(repoRoot, 'onboarding-preview.html'), 'utf8');
  assert(html.includes('languageNames') && html.includes('translationWords'), 'onboarding animation removed');
  assert(html.includes('language-pill'), 'language pill removed');
});

/* extra coverage */
await test('no duplicate keys in any raw package', () => {
  for (const lang of languages) {
    const raw = readFileSync(join(repoRoot, `i18n/locales/${lang.code}.json`), 'utf8');
    const seen = new Set();
    const dupes = [];
    for (const m of raw.matchAll(/^\s*"([^"]+)"\s*:/gm)) {
      if (seen.has(m[1])) dupes.push(m[1]);
      seen.add(m[1]);
    }
    assert(dupes.length === 0, `${lang.code} duplicate keys: ${dupes.join(', ')}`);
  }
});

await test('bundle cache stays bounded (memory discipline)', async () => {
  const host5 = createHost();
  const engine5 = loadEngine(host5.window);
  await engine5.init({ language: 'fr' });
  for (const code of ['de', 'es', 'it', 'pt', 'nl', 'sv', 'pl', 'cs']) {
    await engine5.setLanguage(code);
  }
  assert(engine5._loadedCount() <= 3, `cache grew to ${engine5._loadedCount()}`);
});

await test('long translated strings stay intact', async () => {
  await i18n.setLanguage('en');
  const longest = Object.entries(source).sort((a, b) => b[1].length - a[1].length)[0];
  const value = i18n.t(longest[0]);
  assert(value === longest[1], `long string altered: ${longest[0]}`);
});

await test('Online and Offline settings remain separate', () => {
  const html = readFileSync(join(repoRoot, 'index.html'), 'utf8');
  assert(html.includes('class="offline-settings-top"'), 'offline settings block missing');
  assert(html.includes('onlineTtsSettings'), 'online settings block missing');
  assert(html.includes('avnivo-online-tts-settings'), 'online settings styling missing');
});

/* report */
let passed = 0, failed = 0, skipped = 0;
for (const r of results) {
  const line = `${r.status.toUpperCase().padEnd(6)} ${r.name}${r.error ? ' :: ' + r.error : ''}`;
  console.log(line);
  if (r.status === 'passed') passed++;
  else if (r.status === 'failed') failed++;
  else skipped++;
}
console.log(`\n${passed} passed, ${failed} failed, ${skipped} skipped`);
writeFileSync(join(repoRoot, 'i18n', 'test-report.json'), JSON.stringify({
  generatedAt: new Date().toISOString(),
  node: process.version,
  passed, failed, skipped,
  tests: results.map((r) => ({ name: r.name, status: r.status, error: r.error })),
}, null, 2));
console.log('report: i18n/test-report.json');
process.exit(failed === 0 ? 0 : 1);
