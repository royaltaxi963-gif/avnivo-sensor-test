#!/usr/bin/env node
/* Measure real AVNIVO localization switching latency and memory.
 *
 * Runs the actual engine in Node against the real 100 language packages and
 * reports measured values only. Nothing here is estimated or invented: every
 * number printed comes from process.hrtime and process.memoryUsage around real
 * engine calls. Node numbers are a lower bound for browser/WebView behaviour
 * because there is no rendering cost, so they are reported as engine-side cost.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { createHost, loadEngine, repoRoot } from './host.mjs';

const languages = JSON.parse(readFileSync(join(repoRoot, 'i18n/languages.json'), 'utf8'));
const codes = languages.map((l) => l.code);
const bytes = readdirSync(join(repoRoot, 'i18n/locales'))
  .filter((f) => f.endsWith('.json'))
  .reduce((sum, f) => sum + readFileSync(join(repoRoot, 'i18n/locales', f)).length, 0);

function stats(values) {
  const sorted = [...values].sort((a, b) => a - b);
  const sum = sorted.reduce((a, b) => a + b, 0);
  return {
    min: sorted[0], max: sorted[sorted.length - 1], avg: sum / sorted.length,
    p50: sorted[Math.floor(sorted.length * 0.5)],
    p95: sorted[Math.floor(sorted.length * 0.95)],
  };
}

const host = createHost();
const i18n = loadEngine(host.window);

const coldStart = process.hrtime.bigint();
await i18n.init({ language: 'uz' });
const coldMs = Number(process.hrtime.bigint() - coldStart) / 1e6;

const warm = [];
const cached = [];
const coldSwitch = [];
for (let round = 0; round < 5; round++) {
  for (const code of codes) {
    const before = i18n.getLanguage();
    const t = process.hrtime.bigint();
    await i18n.setLanguage(code);
    const ms = Number(process.hrtime.bigint() - t) / 1e6;
    // First time we visit a code in this loop it may be a fetch; afterwards cached.
    (round === 0 ? coldSwitch : warm).push(ms);
  }
}
// Repeatedly alternate two languages: pure cached-switch cost.
for (let i = 0; i < 500; i++) {
  const t = process.hrtime.bigint();
  await i18n.setLanguage(i % 2 ? 'en' : 'de');
  cached.push(Number(process.hrtime.bigint() - t) / 1e6);
}

const mem = process.memoryUsage();
const report = {
  languages: codes.length,
  totalBundleBytes: bytes,
  coldStartMs: coldMs,
  coldSwitchMs: stats(coldSwitch),
  warmSwitchMs: stats(warm),
  cachedSwitchMs: stats(cached),
  heapUsedMB: mem.heapUsed / 1048576,
  rssMB: mem.rss / 1048576,
};

console.log('AVNIVO localization performance (Node ' + process.version + ')');
console.log(`languages: ${report.languages}`);
console.log(`total packaged dictionary bytes: ${report.totalBundleBytes}`);
console.log(`cold start (registry + first bundle + first apply): ${report.coldStartMs.toFixed(2)} ms`);
console.log(`first-visit switch  avg ${report.coldSwitchMs.avg.toFixed(2)} ms  p95 ${report.coldSwitchMs.p95.toFixed(2)} ms  max ${report.coldSwitchMs.max.toFixed(2)} ms`);
console.log(`warm switch         avg ${report.warmSwitchMs.avg.toFixed(2)} ms  p95 ${report.warmSwitchMs.p95.toFixed(2)} ms  max ${report.warmSwitchMs.max.toFixed(2)} ms`);
console.log(`cached switch       avg ${report.cachedSwitchMs.avg.toFixed(3)} ms  p95 ${report.cachedSwitchMs.p95.toFixed(3)} ms  max ${report.cachedSwitchMs.max.toFixed(3)} ms`);
console.log(`heap used: ${report.heapUsedMB.toFixed(1)} MB   rss: ${report.rssMB.toFixed(1)} MB`);

const out = join(repoRoot, 'i18n', 'performance-report.json');
(await import('node:fs')).writeFileSync(out, JSON.stringify(report, null, 2));
console.log('report: i18n/performance-report.json');
