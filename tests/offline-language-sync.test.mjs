/*
 * Node test: language synchronisation and offline module gating, executed against the code that
 * actually ships in assets/index.html.
 *
 * The functions are lifted out of the real file by name, so this cannot pass while the shipped
 * implementation differs. Nothing here is re-implemented in the test.
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const html = readFileSync(join(here, '../android-app/app/src/main/assets/index.html'), 'utf8')

/** Cut a `function name(...) { ... }` declaration, braces matched, straight from the asset. */
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

/** Cut a `const NAME = ...;` declaration, respecting nested braces. */
function cutConst(name) {
  const start = html.indexOf(`const ${name}=`)
  assert.ok(start > 0, `const ${name} must exist in index.html`)
  const semiFirst = html.indexOf(';', start)
  const open = html.indexOf('{', start)
  // A string const has no brace before its semicolon; take the whole statement.
  if (open < 0 || semiFirst < open) return html.slice(start, semiFirst + 1)
  let depth = 0
  for (let i = open; i < html.length; i += 1) {
    if (html[i] === '{') depth += 1
    else if (html[i] === '}') {
      depth -= 1
      if (depth === 0) return html.slice(start, html.indexOf(';', i) + 1)
    }
  }
  throw new Error(`unterminated const ${name}`)
}

const REAL_NLLB_CODES = ['rus_Cyrl', 'uzn_Latn', 'eng_Latn', 'deu_Latn', 'zho_Hans']

/**
 * Loads the shipped language logic with a controllable native bridge. `mode` is the current mode
 * the DOM reports, which is all the transfer logic keys off.
 */
function load({ mode = 'online', online = { left: 'uz-RU', right: 'ru-RU' }, offline = {}, bridge } = {}) {
  const saved = new Map()
  const state = {
    mode,
    online: { left: 'uz-UZ', right: 'ru-RU', ...online },
    offline: { left: null, right: null, ...offline },
    supported: null,
    storage: saved,
  }
  const noop = () => {}
  const sandbox = {
    state,
    document: { documentElement: { dataset: { avnivoMode: mode } } },
    localStorage: {
      getItem: (k) => (saved.has(k) ? saved.get(k) : null),
      setItem: (k, v) => saved.set(k, v),
    },
    console,
    ONLINE_LANGUAGES: [
      ['uz-UZ', 'uz', 'official'],
      ['ru-RU', 'ru', 'official'],
      ['de-DE', 'de', 'official'],
      ['en-US', 'en-US', 'official'],
      ['cmn-Hans-CN', 'zh', 'official'],
      ['cmn-Hant-TW', 'zh-TW', 'official'],
      ['pa-Guru-IN', 'pa', 'official'],
    ],
    onlineChoice: state.online,
    onlineLanguageName: (locale) => locale,
    avnivoUiText: (_key, fallback) => fallback,
    persistOnlineLanguagePair: () => saved.set('avnivo.online.language-pair.v1', JSON.stringify(state.online)),
    window: { AvnivoOfflineModules: bridge },
  }
  // `offlineChoice` is a real const in the asset; reuse it but seed it from the test.
  const source = [
    cutConst('NLLB_BY_BASE'),
    cutConst('NLLB_BY_LOCALE'),
    cutFunction('nllbCodeForLocale'),
    cutFunction('offlineTranslationState'),
    cutFunction('offlineLanguagesReady'),
    cutConst('offlineLanguageStorageKey'),
    cutConst('offlineChoice'),
    cutFunction('persistOfflineLanguagePair'),
    cutFunction('supportedOfflineCode'),
    cutFunction('offlineCodeFromLocale'),
    cutFunction('syncLanguageChips'),
    cutFunction('transferLanguageSelection'),
    cutFunction('offlinePairIncomplete'),
  ].join('\n')
  const factory = new Function(
    ...Object.keys(sandbox),
    `${source}
     return {
       offlineChoice,
       syncLanguageChips,
       transferLanguageSelection,
       offlinePairIncomplete,
       nllbCodeForLocale,
       offlineLanguagesReady,
       offlineTranslationState,
     }`
  )
  const api = factory(...Object.values(sandbox))
  Object.assign(api.offlineChoice, state.offline)
  state.api = api
  return state
}

/** A ready bridge carrying the given codes; `state` defaults to the tokenizer's real set. */
function readyBridge({ ready = true, languages = REAL_NLLB_CODES, status = { state: 'ready' } } = {}) {
  return {
    offlineTranslationState: () => JSON.stringify({ ready, languages, status }),
  }
}

test('maps BCP-47 locales to the NLLB codes the tokenizer carries', () => {
  const s = load()
  const { nllbCodeForLocale } = s.api
  assert.equal(nllbCodeForLocale('uz-UZ'), 'uzn_Latn')
  assert.equal(nllbCodeForLocale('ru-RU'), 'rus_Cyrl')
  assert.equal(nllbCodeForLocale('de-DE'), 'deu_Latn')
  // Script must not be guessed: Chinese keeps Hans/Hant, Serbian stays Cyrillic.
  assert.equal(nllbCodeForLocale('cmn-Hans-CN'), 'zho_Hans')
  assert.equal(nllbCodeForLocale('cmn-Hant-TW'), 'zho_Hant')
  assert.equal(nllbCodeForLocale('sr-RS'), 'srp_Cyrl')
  assert.equal(nllbCodeForLocale('pa-Guru-IN'), 'pan_Guru')
})

test('a locale NLLB does not cover maps to nothing rather than borrowing a language', () => {
  const s = load()
  assert.equal(s.api.nllbCodeForLocale('xx-XX'), null)
  assert.equal(s.api.nllbCodeForLocale(''), null)
  assert.equal(s.api.nllbCodeForLocale(undefined), null)
})

test('no module installed: the picker has no languages and is not ready', () => {
  const s = load({ bridge: readyBridge({ ready: false, languages: [], status: { state: 'not_installed' } }) })
  const state = s.api.offlineTranslationState()
  assert.equal(state.ready, false)
  assert.deepEqual(state.languages, [])
  assert.equal(s.api.offlineLanguagesReady(), null)
})

test('a bridge that is absent entirely is treated as not installed, not as ready', () => {
  const s = load({ bridge: undefined })
  const state = s.api.offlineTranslationState()
  assert.equal(state.ready, false)
  assert.deepEqual(state.languages, [])
})

test('a module claiming ready with an empty list cannot open the picker', () => {
  const s = load({ bridge: readyBridge({ ready: true, languages: [] }) })
  assert.equal(s.api.offlineLanguagesReady(), null)
})

test('ready module exposes exactly the codes the tokenizer reported', () => {
  const s = load({ bridge: readyBridge({ languages: ['uzn_Latn', 'rus_Cyrl'] }) })
  assert.deepEqual(s.api.offlineLanguagesReady(), ['uzn_Latn', 'rus_Cyrl'])
})

test('online -> offline carries the compatible selection and keeps the online pair', () => {
  const s = load({ mode: 'offline', online: { left: 'uz-UZ', right: 'ru-RU' }, bridge: readyBridge() })
  s.api.transferLanguageSelection('online', 'offline')
  assert.equal(s.api.offlineChoice.left, 'uzn_Latn')
  assert.equal(s.api.offlineChoice.right, 'rus_Cyrl')
  // The online pair is untouched, so returning to online restores it.
  assert.equal(s.online.left, 'uz-UZ')
  assert.equal(s.online.right, 'ru-RU')
})

test('online -> offline leaves an unmapped side empty without deleting the online choice', () => {
  const s = load({ mode: 'offline', online: { left: 'xx-XX', right: 'ru-RU' }, bridge: readyBridge() })
  s.api.transferLanguageSelection('online', 'offline')
  assert.equal(s.api.offlineChoice.left, null)
  assert.equal(s.api.offlineChoice.right, 'rus_Cyrl')
  assert.equal(s.online.left, 'xx-XX')
})

test('a code the tokenizer does not carry is not transferred', () => {
  const s = load({ mode: 'offline', online: { left: 'de-DE', right: 'ru-RU' }, bridge: readyBridge({ languages: ['uzn_Latn', 'rus_Cyrl'] }) })
  s.api.transferLanguageSelection('online', 'offline')
  assert.equal(s.api.offlineChoice.left, null)
  assert.equal(s.api.offlineChoice.right, 'rus_Cyrl')
})

test('offline -> online transfers a filled side back and keeps the rest', () => {
  const s = load({ mode: 'online', offline: { left: 'deu_Latn', right: null }, online: { left: 'uz-UZ', right: 'ru-RU' }, bridge: readyBridge() })
  s.api.transferLanguageSelection('offline', 'online')
  assert.equal(s.online.left, 'de-DE')
  assert.equal(s.online.right, 'ru-RU', 'an empty offline side must not clobber the online choice')
})

test('online -> offline -> online round trip restores the original online pair', () => {
  const s = load({ mode: 'online', online: { left: 'de-DE', right: 'en-US' }, bridge: readyBridge() })
  s.api.transferLanguageSelection('online', 'offline')
  s.api.transferLanguageSelection('offline', 'online')
  assert.equal(s.online.left, 'de-DE')
  assert.equal(s.online.right, 'en-US')
})

test('offline -> online -> offline round trip restores the original offline pair', () => {
  const s = load({ mode: 'offline', offline: { left: 'deu_Latn', right: 'eng_Latn' }, bridge: readyBridge() })
  s.api.transferLanguageSelection('offline', 'online')
  s.api.transferLanguageSelection('online', 'offline')
  assert.equal(s.api.offlineChoice.left, 'deu_Latn')
  assert.equal(s.api.offlineChoice.right, 'eng_Latn')
})

test('send is refused while either offline language is missing', () => {
  const s = load({ mode: 'offline', offline: { left: 'uzn_Latn', right: null }, bridge: readyBridge() })
  assert.equal(s.api.offlinePairIncomplete(), true)
})

test('send is refused when the module is not ready even with a stored pair', () => {
  const s = load({
    mode: 'offline',
    offline: { left: 'uzn_Latn', right: 'rus_Cyrl' },
    bridge: readyBridge({ ready: false, languages: [], status: { state: 'error' } }),
  })
  assert.equal(s.api.offlinePairIncomplete(), true)
})

test('send is allowed once both offline languages resolve', () => {
  const s = load({ mode: 'offline', offline: { left: 'uzn_Latn', right: 'rus_Cyrl' }, bridge: readyBridge() })
  assert.equal(s.api.offlinePairIncomplete(), false)
})

test('online mode never blocks send on the offline module', () => {
  const s = load({ mode: 'online', bridge: undefined })
  assert.equal(s.api.offlinePairIncomplete(), false)
})

test('the offline pair is persisted under its own key, not the online one', () => {
  const s = load({ mode: 'offline', bridge: readyBridge() })
  s.api.transferLanguageSelection('online', 'offline')
  assert.ok(s.storage.has('avnivo.offline.language-pair.v1'))
  // Each mode keeps its own record, so a later switch back has something to restore.
  assert.deepEqual(JSON.parse(s.storage.get('avnivo.offline.language-pair.v1')), { left: 'uzn_Latn', right: 'rus_Cyrl' })
  assert.deepEqual(JSON.parse(s.storage.get('avnivo.online.language-pair.v1')), { left: 'uz-RU', right: 'ru-RU' })
})

test('a stored offline pair is restored on the next launch', () => {
  const saved = new Map([['avnivo.offline.language-pair.v1', JSON.stringify({ left: 'deu_Latn', right: 'eng_Latn' })]])
  const first = load({ mode: 'offline', bridge: readyBridge() })
  // simulate a relaunch by re-running the declaration against the saved value
  const start = html.indexOf('const offlineLanguageStorageKey=')
  const end = html.indexOf('function persistOfflineLanguagePair')
  const decl = html.slice(start, end)
  const run = new Function(
    'localStorage',
    `${decl}\nreturn offlineChoice`
  )
  const restored = run({ getItem: (k) => saved.get(k) ?? null, setItem: () => {} })
  assert.deepEqual(restored, { left: 'deu_Latn', right: 'eng_Latn' })
  assert.ok(first.storage instanceof Map)
})

test('chip rendering blanks an unsupported side instead of showing a false language', () => {
  const s = load({
    mode: 'offline',
    offline: { left: 'deu_Latn', right: 'rus_Cyrl' },
    bridge: readyBridge({ languages: ['uzn_Latn', 'rus_Cyrl'] }),
  })
  const chips = {
    left: { name: '', code: '', attrs: {}, removeAttribute(k) { delete this.attrs[k] }, setAttribute(k, v) { this.attrs[k] = v } },
    right: { name: '', code: '', attrs: {}, removeAttribute(k) { delete this.attrs[k] }, setAttribute(k, v) { this.attrs[k] = v } },
  }
  const buttons = {
    left: { querySelector: (sel) => (sel === '.language-name' ? { set textContent(v) { chips.left.name = v } } : { set textContent(v) { chips.left.code = v } }), setAttribute: chips.left.setAttribute.bind(chips.left), removeAttribute: chips.left.removeAttribute.bind(chips.left) },
    right: { querySelector: (sel) => (sel === '.language-name' ? { set textContent(v) { chips.right.name = v } } : { set textContent(v) { chips.right.code = v } }), setAttribute: chips.right.setAttribute.bind(chips.right), removeAttribute: chips.right.removeAttribute.bind(chips.right) },
  }
  const run = new Function(
    'document', 'leftLanguage', 'rightLanguage', 'offlineLanguagesReady', 'supportedOfflineCode', 'offlineDisplayName', 'avnivoUiText', 'onlineChoice', 'offlineChoice', 'onlineLanguageName',
    `${cutFunction('syncLanguageChips')}\nsyncLanguageChips()`
  )
  run(
    { documentElement: { dataset: { avnivoMode: 'offline' } } },
    buttons.left, buttons.right,
    () => ['uzn_Latn', 'rus_Cyrl'],
    (code, sup) => (code && sup && sup.includes(code) ? code : null),
    (code) => (code === 'rus_Cyrl' ? 'Rus tili' : ''),
    (_k, f) => f,
    s.online, s.api.offlineChoice, (l) => l
  )
  assert.equal(chips.left.code, '', 'an unsupported language must render as a blank chip')
  assert.equal(chips.left.attrs['data-unsupported'], 'true')
  assert.equal(chips.right.code, 'RUS')
  assert.equal(chips.right.attrs['data-unsupported'], undefined)
})
