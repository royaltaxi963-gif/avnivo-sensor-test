/*
 * Node tests: the Uzbek STT module card is driven by real native state, not by wording.
 *
 * The S23 Ultra report showed the module card and the recording indicator disagreeing with the
 * engine: a status string could end a live recording, the card could not read its own native
 * status, and recording/decoding had no visible state. These assertions pin the contract on both
 * sides of the bridge so the card cannot drift from the engine again.
 */
const { test } = require('node:test');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const path = require('node:path');

const root = path.join(__dirname, '..');
const main = path.join(root, 'android-app', 'app', 'src', 'main', 'java', 'com', 'avnivo', 'translator');
const activity = fs.readFileSync(path.join(main, 'MainActivity.kt'), 'utf8');
const controller = fs.readFileSync(path.join(main, 'UzbekSttController.kt'), 'utf8');
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

const statusHandler = functionBody(shipped, 'window.AvnivoOfflineStatus=function(message,session)');
const stateHandler = functionBody(shipped, 'window.AvnivoOfflineState=function(raw,session)');
const rejectedHandler = functionBody(shipped, 'window.AvnivoOfflineRejected=function(message,session)');
const errorHandler = functionBody(shipped, 'window.AvnivoOfflineError=function(message,session)');
const resultHandler = functionBody(shipped, 'window.AvnivoOfflineResult=function(text,session)');
const bridge = functionBody(activity, 'inner class Bridge {');
const trim = functionBody(activity, 'override fun onTrimMemory(level: Int)');
const resume = functionBody(activity, 'override fun onResume()');
const cancel = functionBody(activity, '@JavascriptInterface fun cancel(session: Int = 0)');
const transcribe = functionBody(activity, 'private fun transcribeCaptured(');
const ensureReady = functionBody(controller, 'fun ensureReady(session: Int = 0): SttRecognizer');
const release = functionBody(controller, 'fun releaseForMemory(): Boolean');

test('1. a progress status never ends a live recording', () => {
  // The old handler finished the press on every status string that did not contain "yozib olin".
  assert.doesNotMatch(statusHandler, /finishOfflineHold/, 'progress text must not clear the press');
  assert.doesNotMatch(statusHandler, /offlineProcessingSession/, 'progress text must not clear the stage');
});

test('2. only an explicit rejection ends a refused press', () => {
  assert.match(rejectedHandler, /finishOfflineHold\(\)/);
  assert.match(rejectedHandler, /offlineStateName\('error'\)/);
});

test('3. the native state callback drives the card and its reason', () => {
  assert.match(stateHandler, /updateOfflineModule\('uz-stt',state\)/);
  assert.match(stateHandler, /showUzbekSttReason\(info\.reason\)/);
  assert.match(stateHandler, /info\.readyForSpeech/);
  assert.match(stateHandler, /offlineStateName\('recording'\)/);
  assert.match(stateHandler, /offlineStateName\('loading'\)/);
});

test('4. the card accepts every native state', () => {
  const keys = shipped.match(/const offlineStateKeys=\{([^}]*)\}/);
  assert.ok(keys, 'the state whitelist must exist');
  for (const state of ['not_installed', 'downloading', 'verifying', 'installed', 'initializing', 'ready', 'enabled', 'recording', 'transcribing', 'error']) {
    assert.ok(keys[1].includes(`${state}:`), `${state} must be drawable`);
  }
});

test('5. recording and decoding keep the module in the active list', () => {
  const active = shipped.match(/const offlineActiveStates=new Set\(\[([^\]]*)\]\)/);
  assert.ok(active, 'the active-state set must exist');
  for (const state of ['enabled', 'ready', 'recording', 'transcribing']) {
    assert.ok(active[1].includes(`'${state}'`), `${state} must stay active`);
  }
});

test('6. moduleStatus is reachable from the page', () => {
  // Only methods of the object passed to addJavascriptInterface are exposed. A copy on the
  // activity was invisible to the page, which is why the card could not read its native state.
  assert.match(bridge, /fun moduleStatus\(\): String/);
  assert.match(activity, /addJavascriptInterface\(Bridge\(\), "AvnivoOfflineSTT"\)/);
  assert.doesNotMatch(activity, /\n    @JavascriptInterface\n    fun moduleStatus/,
    'moduleStatus must live on the registered bridge object');
});

test('7. memory pressure releases the engine and the next use reloads it', () => {
  assert.match(trim, /sttController\.releaseForMemory\(\)/);
  assert.match(release, /closeRecognizerLocked\(\)/);
  assert.match(ensureReady, /recognizer\?\.let \{ if \(recognizerVerified\) return it \}/,
    'a released engine must be rebuilt on the next use');
});

test('8. resume re-announces the real state', () => {
  assert.match(resume, /sttController\.refreshInstalled\(\)/);
  assert.match(resume, /notifyJs\("AvnivoOfflineState"/);
});

test('9. a cancelled press discards its audio', () => {
  assert.match(cancel, /discardSession = target/);
  assert.match(transcribe, /if \(discardSession == session\)/);
  assert.match(transcribe, /discardSession = 0/);
});

test('10. every native state has wording in all three bundles', () => {
  for (const state of ['initializing', 'recording', 'transcribing']) {
    const key = `"offline.state.${state}"`;
    const occurrences = shipped.split(key).length - 1;
    assert.ok(occurrences >= 3, `${key} must be translated in uz, ru and en, found ${occurrences}`);
  }
});

test('11. an error keeps the card state and still shows its reason', () => {
  assert.match(errorHandler, /syncUzbekSttCard\(\)/);
  assert.match(errorHandler, /showUzbekSttReason\(error\)/);
  assert.match(resultHandler, /syncUzbekSttCard\(\)/);
});

test('12. startup READY does not end a live recording', () => {
  // Engine verification can finish while the user is already holding the microphone. Only the
  // READY for the press that is being decoded may close it.
  assert.match(stateHandler, /offlineProcessingSession===offlineSession/);
});
