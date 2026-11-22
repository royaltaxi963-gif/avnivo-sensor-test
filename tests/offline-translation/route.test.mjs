// Verifies the offline translation routing that ships inside assets/index.html, executed against
// a stubbed native bridge. The block is extracted from the real file, so this test cannot pass
// while the shipped code differs.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const html = readFileSync(join(here, '../../android-app/app/src/main/assets/index.html'), 'utf8')

const START = 'const PREVIEW_TRANSLATION='
const END = "typeTranslation(bubble,payload.text,()=>autoReadTranslation(bubble,'offline',pending.historyId));\n};"
const AUTO_READ = () => {}
const startIndex = html.indexOf(START)
const endIndex = html.indexOf(END)
assert.ok(startIndex > 0 && endIndex > startIndex, 'offline translation block must exist in index.html')
const source = html.slice(startIndex, endIndex + END.length)

// The block only touches these, so a small harness is enough to run it for real.
function load({ bridge, ...options }) {
  const typed = []
  const persisted = []
  const sandbox = {
    typed,
    persisted,
    oppositeLanguage: language => (language === 'right' ? 'left' : 'right'),
    typeTranslation: (bubble, text) => { typed.push(text); bubble.textContent = text },
    persistHistory: () => { persisted.push(true) },
    window: { AvnivoOfflineModules: withState(bridge, options.supported) },
    translateOnlineText: () => assert.fail('offline path must never call the online endpoint'),
    // The block declares offlineChoice, offlineLanguagesReady and supportedOfflineCode itself; the
    // only lever a test has is the native bridge, which is exactly what production reads too.
    supportedOfflineCode: (code, supported) => (code && supported && supported.includes(code) ? code : null),
    avnivoUiText: (_key, fallback) => fallback,
    autoReadTranslation: () => {},
    syncLanguageChips: () => {},
    currentHistoryId: 'h1',
    localStorage: { getItem: () => null, setItem: () => {} },
    console,
  }
  const factory = new Function(
    ...Object.keys(sandbox),
    `${source}\nreturn {
       translateOfflineText,
       fire: raw => window.AvnivoOfflineTranslation(raw),
       pending: () => offlineTranslations.size,
       placeholder: PREVIEW_TRANSLATION,
       choice: offlineChoice,
       typed, persisted
     }`
  )
  const api = factory(...Object.values(sandbox))
  const pair = options.pair ?? { left: 'uzn_Latn', right: 'rus_Cyrl' }
  Object.assign(api.choice, pair)
  return api
}

/** The picker's truth comes from the bridge; give it the same shape the Kotlin side returns. */
function withState(bridge, supported) {
  if (!bridge) return undefined
  const languages = supported ?? ['uzn_Latn', 'rus_Cyrl']
  return { ...bridge, offlineTranslationState: () => JSON.stringify({ ready: true, languages, status: { state: 'ready' } }) }
}

const NOT_READY = 'Offline tarjima moduli yuklab olinmagan.'
const bubble = () => ({ textContent: '', classList: { remove() {} }, isConnected: true })

test('uses the real engine result, not the placeholder', () => {
  let request
  const api = load({
    bridge: {
      supportsTranslation: (s, t) => s === 'uzn_Latn' && t === 'rus_Cyrl',
      translate: (text, s, t, id) => { request = { text, s, t, id } },
    },
  })
  const el = bubble()
  api.translateOfflineText('Salom', 'left', el, null)

  assert.equal(request.text, 'Salom')
  assert.equal(request.s, 'uzn_Latn')
  assert.equal(request.t, 'rus_Cyrl')
  assert.equal(el.textContent, 'Tarjima qilinmoqda…')

  api.fire(JSON.stringify({ requestId: request.id, text: 'Привет' }))
  assert.deepEqual(api.typed, ['Привет'])
  assert.equal(api.pending(), 0)
})

test('persists the verified translation into history', () => {
  let request
  const api = load({
    bridge: { supportsTranslation: () => true, translate: (_t, _s, _x, id) => { request = { id } } },
  })
  const entry = {}
  api.translateOfflineText('Rahmat', 'left', bubble(), entry)
  api.fire(JSON.stringify({ requestId: request.id, text: 'Спасибо' }))
  assert.equal(entry.translation, 'Спасибо')
  assert.equal(api.persisted.length, 1)
})

test('refuses with a reason when no bridge exists', () => {
  const api = load({ bridge: undefined })
  const el = bubble()
  api.translateOfflineText('Salom', 'left', el, null)
  assert.equal(el.textContent, NOT_READY)
  assert.equal(api.pending(), 0)
})

test('refuses with a reason when the engine cannot translate the pair', () => {
  const api = load({
    bridge: { supportsTranslation: () => false, translate: () => assert.fail('must not call translate') },
  })
  const el = bubble()
  api.translateOfflineText('Salom', 'left', el, null)
  assert.equal(el.textContent, NOT_READY)
})

test('a throwing supportsTranslation is treated as unsupported', () => {
  const api = load({
    bridge: {
      supportsTranslation: () => { throw new Error('native died') },
      translate: () => assert.fail('must not call translate'),
    },
  })
  const el = bubble()
  api.translateOfflineText('Salom', 'left', el, null)
  assert.equal(el.textContent, NOT_READY)
})

test('surfaces a native error instead of faking a translation', () => {
  let request
  const api = load({
    bridge: { supportsTranslation: () => true, translate: (_t, _s, _x, id) => { request = { id } } },
  })
  const el = bubble()
  api.translateOfflineText('Salom', 'left', el, null)
  api.fire(JSON.stringify({ requestId: request.id, error: 'Engine not ready' }))
  assert.deepEqual(api.typed, [])
  assert.match(el.textContent, /Engine not ready/)
  assert.notEqual(el.textContent, api.placeholder)
})

test('treats a blank result as a failure', () => {
  let request
  const api = load({
    bridge: { supportsTranslation: () => true, translate: (_t, _s, _x, id) => { request = { id } } },
  })
  const el = bubble()
  api.translateOfflineText('Salom', 'left', el, null)
  api.fire(JSON.stringify({ requestId: request.id, text: '   ' }))
  assert.deepEqual(api.typed, [])
  assert.match(el.textContent, /bajarilmadi/)
})

test('ignores responses for requests it did not send', () => {
  const api = load({ bridge: { supportsTranslation: () => true, translate: () => {} } })
  api.fire(JSON.stringify({ requestId: '999', text: 'Привет' }))
  assert.deepEqual(api.typed, [])
})

test('ignores malformed bridge payloads', () => {
  const api = load({ bridge: { supportsTranslation: () => true, translate: () => {} } })
  api.fire('not json')
  api.fire('')
  assert.deepEqual(api.typed, [])
})

test('concurrent requests resolve independently', () => {
  const ids = []
  const api = load({
    bridge: { supportsTranslation: () => true, translate: (_t, _s, _x, id) => ids.push(id) },
  })
  const first = bubble()
  const second = bubble()
  api.translateOfflineText('Salom', 'left', first, null)
  api.translateOfflineText('Rahmat', 'left', second, null)
  assert.equal(ids.length, 2)
  assert.notEqual(ids[0], ids[1])

  api.fire(JSON.stringify({ requestId: ids[1], text: 'Спасибо' }))
  assert.equal(second.textContent, 'Спасибо')
  assert.equal(first.textContent, 'Tarjima qilinmoqda…')

  api.fire(JSON.stringify({ requestId: ids[0], text: 'Привет' }))
  assert.equal(first.textContent, 'Привет')
  assert.equal(api.pending(), 0)
})

test('a detached bubble does not throw and still persists', () => {
  let request
  const api = load({
    bridge: { supportsTranslation: () => true, translate: (_t, _s, _x, id) => { request = { id } } },
  })
  const el = bubble()
  const entry = {}
  api.translateOfflineText('Salom', 'left', el, entry)
  el.isConnected = false
  api.fire(JSON.stringify({ requestId: request.id, text: 'Привет' }))
  assert.equal(entry.translation, 'Привет')
})
