/*
 * Node test: the offline picker's source is the full audited NLLB tokenizer registry, not the
 * ready-module list and not an STT model's language list. The registry is parsed from the shipped
 * Kotlin file, so this test cannot pass against a different list than the APK contains.
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const root = join(here, '..')
const html = readFileSync(join(root, 'android-app/app/src/main/assets/index.html'), 'utf8')
const kotlin = readFileSync(
  join(root, 'android-app/app/src/main/java/com/avnivo/translator/NllbLanguageRegistry.kt'),
  'utf8'
)

function cutFunction(name) {
  const start = html.indexOf(`function ${name}(`)
  assert.ok(start > 0, `function ${name} must exist in index.html`)
  const open = html.indexOf('{', start)
  let depth = 0
  for (let i = open; i < html.length; i += 1) {
    if (html[i] === '{') depth += 1
    else if (html[i] === '}') {
      depth -= 1
      if (depth === 0) return html.slice(start, i + 1)
    }
  }
  throw new Error(`unterminated function ${name}`)
}

function cutConst(name) {
  const start = html.indexOf(`const ${name}=`)
  assert.ok(start > 0, `const ${name} must exist in index.html`)
  const semi = html.indexOf(';', start)
  return html.slice(start, semi + 1)
}

function shippedRegistry() {
  const rows = [...kotlin.matchAll(
    /NllbLanguage\("([^"]+)",\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]+)",\s*(null|"[^"]*"),\s*"([^"]+)"\)/g
  )]
  return rows.map(([, code, name, script, scriptCode, whisper, locale]) => ({
    code,
    name,
    script,
    scriptCode,
    whisper: whisper === 'null' ? null : whisper.slice(1, -1),
    locale,
  }))
}

function load({ registry = shippedRegistry(), ready = false, languages = [], bridge = true } = {}) {
  const sandbox = {
    window: bridge
      ? {
          AvnivoOfflineModules: {
            offlineLanguageRegistry: () => JSON.stringify({ schema: 1, languages: registry }),
            offlineTranslationState: () => JSON.stringify({ ready, languages, status: {} }),
          },
        }
      : {},
    document: { documentElement: { dataset: { avnivoMode: 'offline' } } },
    ONLINE_LANGUAGES: [
      ['uz-UZ', 'uz', 'official'],
      ['ru-RU', 'ru', 'official'],
      ['de-DE', 'de', 'official'],
    ],
    onlineLanguageName: (locale) => locale,
    avnivoUiText: (_key, fallback) => fallback,
    NLLB_BY_BASE: {},
    NLLB_BY_LOCALE: {},
    nllbCodeForLocale: (locale) => ({
      'uz-UZ': 'uzn_Latn',
      'ru-RU': 'rus_Cyrl',
      'de-DE': 'deu_Latn',
    }[locale] || null),
    offlineChoice: { left: null, right: null },
    localStorage: { getItem: () => null, setItem: () => {} },
    persistOfflineLanguagePair: () => {},
    offlineLanguageStorageKey: 'avnivo.offline.language-pair.v1',
    supportedOfflineCode: (code, supported) => (code && supported && supported.includes(code) ? code : null),
    offlineLanguagesReady: () => (ready && languages.length ? languages : null),
  }
  const source = [
    'var offlineRegistryCache=null;',
    cutFunction('offlineLanguageRegistry'),
    cutFunction('offlineLanguageEntry'),
    cutFunction('offlineTranslationState'),
    cutFunction('offlineDisplayName'),
    cutFunction('offlineCodeLabel'),
    cutFunction('offlineLanguagesForPicker'),
    cutFunction('offlineCodeFromLocale'),
  ].join('\n')
  const factory = new Function(
    ...Object.keys(sandbox),
    `${source}
     return { offlineLanguageRegistry, offlineLanguageEntry, offlineDisplayName,
       offlineCodeLabel, offlineLanguagesForPicker, offlineCodeFromLocale, offlineLanguagesReady }`
  )
  return factory(...Object.values(sandbox))
}

test('the shipped registry has exactly 202 codes and 196 base languages', () => {
  const rows = shippedRegistry()
  assert.equal(rows.length, 202)
  assert.equal(new Set(rows.map((r) => r.code)).size, 202)
  assert.equal(new Set(rows.map((r) => r.code.split('_')[0])).size, 196)
  assert.equal(new Set(rows.map((r) => r.script)).size, 28)
})

test('the picker lists all 202 languages before the NLLB model is installed', () => {
  const api = load({ ready: false, languages: [] })
  const languages = api.offlineLanguagesForPicker()
  assert.equal(languages.length, 202)
  assert.equal(languages[0].code, 'ace_Arab')
  assert.equal(languages.at(-1).code, 'zul_Latn')
  assert.equal(api.offlineLanguagesReady(), null)
})

test('names and scripts come from the registry, not from the STT or online list', () => {
  const api = load()
  assert.equal(api.offlineDisplayName('rus_Cyrl'), 'Russian')
  assert.equal(api.offlineDisplayName('hin_Deva'), 'Hindi')
  assert.equal(api.offlineDisplayName('ben_Beng'), 'Bengali')
  assert.equal(api.offlineDisplayName('ace_Arab'), 'Acehnese (Arabic script)')
  assert.equal(api.offlineLanguageEntry('ace_Arab').script, 'Arabic')
  assert.equal(api.offlineLanguageEntry('ace_Latn').script, 'Latin')
  assert.equal(api.offlineCodeLabel('sat_Beng').script, 'Bengali')
})

test('a new registry code transfers before the model is ready, but still needs the ready list to translate', () => {
  const api = load({ ready: false, languages: [] })
  assert.equal(api.offlineCodeFromLocale('ru-RU', null), 'rus_Cyrl')
  assert.equal(api.offlineCodeFromLocale('de-DE', null), 'deu_Latn')
  const readyApi = load({ ready: true, languages: ['uzn_Latn', 'rus_Cyrl'] })
  assert.equal(readyApi.offlineCodeFromLocale('de-DE', ['uzn_Latn', 'rus_Cyrl']), null)
})

test('a missing native registry falls back to the ready list without inventing languages', () => {
  const api = load({ bridge: false, ready: true, languages: ['uzn_Latn', 'rus_Cyrl'] })
  assert.deepEqual(api.offlineLanguagesForPicker().map((x) => x.code), ['uzn_Latn', 'rus_Cyrl'])
})
