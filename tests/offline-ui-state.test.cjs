/*
 * Node tests: offline interface state that the user can actually see.
 *
 * Three interface defects were reported: the offline language chips looked empty, the chosen
 * languages appeared to be forgotten after reopening, and the greeting animation came back in
 * the middle of a transcription. Each had its own cause, and each is pinned here.
 */
const { test } = require('node:test');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const path = require('node:path');

const root = path.join(__dirname, '..');
const shipped = fs.readFileSync(path.join(root, 'android-app', 'app', 'src', 'main', 'assets', 'index.html'), 'utf8');

function functionBody(source, signature) {
  const start = source.indexOf(signature);
  assert.ok(start !== -1, `expected ${signature} to exist`);
  const open = source.indexOf('{', start);
  assert.ok(open !== -1, `expected ${signature} to have a body`);
  let depth = 0;
  for (let i = open; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1;
    else if (source[i] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(open + 1, i);
    }
  }
  throw new Error(`unbalanced braces in ${signature}`);
}

const chips = functionBody(shipped, 'function syncLanguageChips()');
const composer = functionBody(shipped, 'function syncComposerMode()');
const tick = functionBody(shipped, 'function tick()');

test('1. a stored offline language is shown before the model is installed', () => {
  // Reading only the tokenizer's list meant an uninstalled NLLB model blanked both chips, which
  // the user reported as "the languages look empty".
  assert.match(chips, /const stored=offlineChoice\[side\]/, 'the stored code must be read');
  assert.match(chips, /const code=supported\?supportedOfflineCode\(stored,supported\):stored/,
    'the stored code must survive an unloaded model');
});

test('2. a genuinely unusable language is still marked', () => {
  // Showing the stored pair must not become a promise: when a loaded tokenizer proves it cannot be
  // encoded, the chip is marked so translation refuses with a reason.
  assert.match(chips, /const unusable=Boolean\(stored\)&&supported!==null&&!code/);
  assert.match(chips, /setAttribute\('data-unsupported','true'\)/);
  assert.match(chips, /removeAttribute\('data-unsupported'\)/);
});

test('3. the offline pair is persisted separately from the online pair', () => {
  // Sharing one key is what made a mode switch destroy the other mode's choice.
  assert.match(shipped, /const offlineLanguageStorageKey='avnivo\.offline\.language-pair\.v1'/);
  const persist = functionBody(shipped, 'function persistOfflineLanguagePair()');
  assert.match(persist, /setItem\(offlineLanguageStorageKey/);
  assert.match(shipped, /function loadOfflineLanguagePair|offlineChoice\[side\]=saved\[side\]/,
    'the stored pair must be restored on start');
});

test('4. the greeting stays hidden while a press is being decoded', () => {
  // The greeting returned mid-transcription because only the recording class was checked.
  assert.match(composer, /const busyVoice=active\|\|shell\.classList\.contains\('hold-voice'\)/);
  assert.match(composer, /offlineProcessingSession===offlineSession/, 'decoding must count as active');
  assert.match(composer, /label\.hidden=typing\|\|messages\.children\.length>0\|\|busyVoice/);
});

test('5. the greeting loop agrees with the composer state', () => {
  // Both paths must use the same rule, otherwise the animation restarts on its own timer.
  assert.match(tick, /dataset\.state==="loading"/);
  assert.match(tick, /dataset\.state==="recording"/);
  assert.match(tick, /shell\.classList\.contains\("hold-voice"\)/);
});

test('6. the greeting is hidden once real text exists', () => {
  assert.match(tick, /if\(input\.value\.length\|\|messages\.children\.length/,
    'typed text or a sent message must hide the greeting');
});

test('7. the offline processing stage has a visible style', () => {
  // Without a rule the indicator stayed at display:none, which is the "no second animation" report.
  assert.match(shipped, /html\[data-avnivo-mode=offline\] \.online-stt-status\[data-state=loading\]\{display:flex/);
  assert.match(shipped, /html\[data-avnivo-mode=offline\] \.online-stt-status\[data-state=error\]\{display:flex/);
});

test('8. the original offline appearance is untouched', () => {
  // The fix reuses the existing indicator and its existing animation; nothing is redesigned.
  assert.match(shipped, /@keyframes avnivo-offline-record-pulse/);
  assert.match(shipped, /animation:avnivo-offline-record-pulse 1\.15s ease-in-out infinite/);
  assert.match(shipped, /html\[data-avnivo-mode=offline\] \.online-stt-status\{color:#7cd5ff\}/);
});

test("9. the active line names the module own job, not speech recognition for everything", () => {
  // The device report: the translator card read "Faol — nutqni tanishga tayyor" (active - ready to
  // recognize speech) while it was a translator. One shared string cannot describe three jobs.
  assert.match(shipped, /const offlineReadyKey=module=>module\.id\.indexOf\('offline-translate'\)===0\?'offline\.state\.ready\.translate'/);
  assert.match(shipped, /offlineReadyFallback=module=>[^;]*'Faol — tarjimaga tayyor'/);
  assert.match(shipped, /offlineReadyFallback=module=>[^;]*'Faol — ovozga tayyor'/);
  assert.match(shipped, /"offline\.state\.ready\.translate":"Faol — tarjimaga tayyor"/);
  assert.match(shipped, /"offline\.state\.ready\.tts":"Faol — ovozga tayyor"/);
  assert.match(shipped, /"offline\.state\.ready\.translate":"Active — ready to translate"/);
  assert.match(shipped, /"offline\.state\.ready\.translate":"Активен — готов к переводу"/);
  assert.match(shipped, /status\.textContent=avnivoUiText\(state==='ready'\?offlineReadyKey\(module\)/);
});
