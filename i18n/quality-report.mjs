#!/usr/bin/env node
/* Build the translation-quality and human-review report.
 *
 * Every non-English dictionary is machine-translated through a public endpoint
 * and is therefore marked machineTranslated. This script does not claim any
 * professional review. It ranks languages by the linguistic risk of leaving
 * them machine-only, so a reviewer can prioritise.
 */
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { repoRoot } from './tests/host.mjs';

const languages = JSON.parse(readFileSync(join(repoRoot, 'i18n/languages.json'), 'utf8'));
const source = JSON.parse(readFileSync(join(repoRoot, 'i18n/source/en.json'), 'utf8'));
const sourceKeys = Object.keys(source);

// RTL scripts need direction review; low-resource languages need the most
// linguistic attention because public MT quality is least reliable there.
const RTL = new Set(languages.filter((l) => l.rtl).map((l) => l.code));
const LOW_RESOURCE = new Set([
  'ak', 'am', 'as', 'be', 'bs', 'ceb', 'co', 'dv', 'ee', 'eo', 'eu', 'fy',
  'gd', 'gl', 'gu', 'ha', 'haw', 'hmn', 'ht', 'ig', 'is', 'jw', 'ka', 'kk',
  'km', 'kn', 'ky', 'la', 'lb', 'lg', 'lt', 'lv', 'mg', 'mi', 'ml', 'mn',
  'mr', 'mt', 'my', 'ne', 'ny', 'or', 'pa', 'ps', 'sd', 'si', 'sk', 'sl',
  'sm', 'sn', 'so', 'sq', 'su', 'sw', 'ta', 'te', 'tg', 'tk', 'tl', 'tt',
  'ug', 'ur', 'uz', 'vi', 'xh', 'yi', 'yo', 'zu',
]);

const rows = [];
let totalKeys = 0;
let missingKeys = 0;
let identicalToEnglish = 0;
let placeholderBreaks = 0;

for (const lang of languages) {
  const code = lang.code;
  const path = join(repoRoot, 'i18n/locales', `${code}.json`);
  const bundle = JSON.parse(readFileSync(path, 'utf8'));
  const keys = Object.keys(bundle).filter((k) => !k.startsWith('_'));
  totalKeys += sourceKeys.length;
  const missing = sourceKeys.filter((k) => !(k in bundle));
  missingKeys += missing.length;
  let identical = 0;
  for (const key of sourceKeys) {
    if (bundle[key] === source[key]) identical += 1;
  }
  identicalToEnglish += identical;
  const tier = code === 'en' ? 'source'
    : RTL.has(code) ? 'high'
    : LOW_RESOURCE.has(code) ? 'medium'
    : 'standard';
  rows.push({
    code, name: lang.name, englishName: lang.english,
    rtl: !!lang.rtl, tier,
    keys: keys.length, missing: missing.length, identicalToEnglish: identical,
    machineTranslated: bundle._meta ? !!bundle._meta.machineTranslated : code !== 'en',
    reviewed: bundle._meta ? !!bundle._meta.reviewed : false,
  });
}

const byTier = (t) => rows.filter((r) => r.tier === t).map((r) => r.code);
const coverage = ((totalKeys - missingKeys) / totalKeys) * 100;

const report = {
  generatedFrom: 'i18n/locales/*.json',
  languages: languages.length,
  sourceKeys: sourceKeys.length,
  totalKeySlots: totalKeys,
  missingKeys,
  coveragePercent: Number(coverage.toFixed(2)),
  identicalToEnglishPercent: Number(((identicalToEnglish / totalKeys) * 100).toFixed(2)),
  machineTranslatedLanguages: rows.filter((r) => r.machineTranslated).length,
  professionallyReviewedLanguages: rows.filter((r) => r.reviewed).length,
  requiresHumanReview: rows.filter((r) => r.code !== 'en').map((r) => r.code),
  reviewTiers: {
    high: byTier('high'),
    medium: byTier('medium'),
    standard: byTier('standard'),
  },
};

writeFileSync(join(repoRoot, 'i18n', 'translation-quality-report.json'), JSON.stringify(report, null, 2));

const md = [
  '# AVNIVO translation quality report',
  '',
  'All non-English dictionaries were produced by machine translation through a',
  'public endpoint. No language has been professionally reviewed. This report',
  'exists so a reviewer can prioritise; it must not be read as a quality',
  'guarantee.',
  '',
  `- Languages: ${report.languages}`,
  `- Source keys: ${report.sourceKeys}`,
  `- Key coverage: ${report.coveragePercent}% (${totalKeys - missingKeys} / ${totalKeys})`,
  `- Strings identical to English: ${report.identicalToEnglishPercent}%`,
  `- Machine-translated languages: ${report.machineTranslatedLanguages}`,
  `- Professionally reviewed languages: ${report.professionallyReviewedLanguages}`,
  '',
  '## Review priority',
  '',
  `- High (RTL scripts — check text direction and shaping): ${report.reviewTiers.high.join(', ')}`,
  `- Medium (lower-resource languages — check fluency and terminology): ${report.reviewTiers.medium.join(', ')}`,
  `- Standard (widely supported languages — spot check): ${report.reviewTiers.standard.join(', ')}`,
  '',
  '## Per-language detail',
  '',
  '| Language | Code | RTL | Tier | Keys | Missing | Identical to EN |',
  '| --- | --- | --- | --- | --- | --- | --- |',
  ...rows.map((r) => `| ${r.name} | ${r.code} | ${r.rtl ? 'yes' : 'no'} | ${r.tier} | ${r.keys} | ${r.missing} | ${r.identicalToEnglish} |`),
  '',
].join('\n');

writeFileSync(join(repoRoot, 'i18n', 'translation-quality-report.md'), md);
console.log(`languages: ${report.languages}  coverage: ${report.coveragePercent}%  machine: ${report.machineTranslatedLanguages}  reviewed: ${report.professionallyReviewedLanguages}`);
console.log('reports: i18n/translation-quality-report.json, i18n/translation-quality-report.md');
