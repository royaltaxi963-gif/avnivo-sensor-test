/*
 * Node test: translation markers never reach the bubble, and each message is spoken by a voice
 * that speaks its own language.
 *
 * A physical S23 Ultra showed two defects: the NLLB translation began with the literal language
 * token ("rus_Cyrl"), and a Russian message was read with the bundled Uzbek voice. The helpers are
 * lifted out of the shipped asset and executed, so this cannot pass while index.html differs.
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const html = readFileSync(join(here, '../android-app/app/src/main/assets/index.html'), 'utf8')

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
  const open = html.indexOf('{', start)
  const semiFirst = html.indexOf(';', start)
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

// The marker const contains `{3}` inside its regex, so brace-based cutConst would misread it.
const markerConst = html.slice(
  html.indexOf('const NLLB_LANGUAGE_MARKER='),
  html.indexOf('function stripLanguageMarkers(')
)

function load({ voices = [] } = {}) {
  const window = {
    speechSynthesis: { getVoices: () => voices },
  }
  const source = [
    cutConst('NLLB_BY_BASE'),
    cutConst('NLLB_BY_LOCALE'),
    markerConst,
    cutFunction('stripLanguageMarkers'),
    cutConst('SPEECH_LOCALE_BY_NLLB'),
    cutFunction('speechLocaleForNllb'),
    cutFunction('isUzbekNllb'),
    cutFunction('speechVoiceForLocale'),
  ].join('\n')
  const factory = new Function(
    'window',
    `${source}
     return {
       stripLanguageMarkers,
       speechLocaleForNllb,
       isUzbekNllb,
       speechVoiceForLocale,
     }`
  )
  return factory(window)
}

test('strips a leading NLLB language marker from a translation', () => {
  const { stripLanguageMarkers } = load()
  assert.equal(stripLanguageMarkers('rus_Cyrl Привет, как вы?'), 'Привет, как вы?')
  assert.equal(stripLanguageMarkers('eng_Latn It is very hot today.'), 'It is very hot today.')
  assert.equal(stripLanguageMarkers('uzn_Latn Salom'), 'Salom')
})

test('leaves ordinary translated text untouched', () => {
  const { stripLanguageMarkers } = load()
  for (const text of ['Привет, как вы?', 'Спасибо', 'hello world', 'Bugun havo juda issiq.']) {
    assert.equal(stripLanguageMarkers(text), text)
  }
})

test('a translation that is only a marker becomes empty, so it is treated as a failure', () => {
  const { stripLanguageMarkers } = load()
  assert.equal(stripLanguageMarkers('rus_Cyrl'), '')
  assert.equal(stripLanguageMarkers('   '), '')
})

test('maps each NLLB code to the locale its voice expects', () => {
  const { speechLocaleForNllb } = load()
  assert.equal(speechLocaleForNllb('rus_Cyrl'), 'ru')
  assert.equal(speechLocaleForNllb('uzn_Latn'), 'uz')
  assert.equal(speechLocaleForNllb('eng_Latn'), 'en')
  assert.equal(speechLocaleForNllb(null), null)
})

test('only Uzbek is allowed to reach the bundled Silero voice', () => {
  const { isUzbekNllb } = load()
  assert.equal(isUzbekNllb('uzn_Latn'), true)
  // A Russian message must never be routed to the Uzbek-only engine.
  assert.equal(isUzbekNllb('rus_Cyrl'), false)
  assert.equal(isUzbekNllb('eng_Latn'), false)
  assert.equal(isUzbekNllb(null), false)
})

test('picks a voice whose language matches, and refuses a wrong-language fallback', () => {
  const ru = { lang: 'ru-RU', name: 'Russian' }
  const uz = { lang: 'uz-UZ', name: 'Uzbek' }
  assert.equal(load({ voices: [uz, ru] }).speechVoiceForLocale('ru'), ru)
  assert.equal(load({ voices: [uz, ru] }).speechVoiceForLocale('uz'), uz)
  // Listed voices with no Russian: refuse rather than speak Russian with the Uzbek voice.
  assert.equal(load({ voices: [uz] }).speechVoiceForLocale('ru'), null)
  // No voice list yet: `lang` is the only signal, so let the engine try.
  assert.equal(load({ voices: [] }).speechVoiceForLocale('ru'), undefined)
})

test('playMessage routes offline speech by the message language, not its side', () => {
  const start = html.indexOf('async function playMessage(')
  const end = html.indexOf('ttsTestButton.addEventListener', start)
  assert.ok(start > 0 && end > start, 'playMessage must exist')
  const body = html.slice(start, end)
  // The old side-based routing hardcoded Russian for the right side and Uzbek for the left.
  assert.ok(!/language==='right'\?'ru-RU':'uz-UZ'/.test(body), 'side-based voice routing must be gone')
  assert.match(body, /const code=button\.dataset\.code\|\|offlineChoice\[language\]/,
    'the message language must resolve to its NLLB code, preferring the bubble it was sent with')
  assert.match(body, /isUzbekNllb\(code\)/, 'the native Uzbek engine must be gated by language')
  assert.match(body, /speechLocaleForNllb\(code\)/, 'the browser voice must use the message locale')
  assert.match(body, /const text=bubble\.textContent\.trim\(\)/,
    'the speaker must read the bubble it is attached to, not a shared or last message')
})

test('offline browser playback keeps the selected rate', () => {
  const start = html.indexOf('async function playMessage(')
  const end = html.indexOf('ttsTestButton.addEventListener', start)
  const body = html.slice(start, end)
  assert.match(body, /utterance\.lang=speechLocale;utterance\.rate=Number\(ttsRateSelect\.value\)/,
    'the offline Web Speech fallback must use the same rate control as online')
})

test('each speaker carries the language code of the message it belongs to', () => {
  const start = html.indexOf('function attachSpeaker(')
  const end = html.indexOf('function renderChatBubble(', start)
  assert.ok(start > 0 && end > start, 'attachSpeaker must exist')
  const body = html.slice(start, end)
  assert.match(body, /function attachSpeaker\(bubble,language,code\)/, 'attachSpeaker must accept the code')
  assert.match(body, /if\(code\)button\.dataset\.code=code/, 'the code must be stamped on the button')
  assert.match(html, /line\.append\(bubble,button\)/,
    'each button must stay bound to its own bubble')
  // Sending and history restore must both pass the pinned code through.
  assert.match(html, /attachSpeaker\(sourceBubble,language,sourceCode\)/)
  assert.match(html, /attachSpeaker\(translationBubble,oppositeLanguage\(language\),targetCode\)/)
  assert.match(html, /attachSpeaker\(source,messageData\(entry,firstLanguage\)\.language,entry\.sourceCode\)/)
  assert.match(html, /attachSpeaker\(translationBubble,oppositeLanguage\(language\),entry\.targetCode\)/)
})
