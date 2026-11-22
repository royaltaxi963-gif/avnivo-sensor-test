/*
 * Node tests: one press must produce one transcript.
 *
 * Reported on a Samsung S23 Ultra: the first Uzbek recording produced nothing, the second press
 * showed the first recording's text, and the processing indicator never appeared. Those are not
 * three bugs. They are one chain with three broken links:
 *
 *   1. capture was queued on the same single-thread executor that loads models, so a press waited
 *      behind initialization and the release often arrived before the microphone opened;
 *   2. nothing identified which press a result belonged to, so a late result landed in a later
 *      press — the second press showed the first recording;
 *   3. the release cleared every visual state, so the decoding stage was invisible.
 *
 * These assertions pin the session contract on both sides of the bridge, so the chain cannot
 * silently come apart again.
 */
const { test } = require('node:test');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const path = require('node:path');

const root = path.join(__dirname, '..');
const main = path.join(root, 'android-app', 'app', 'src', 'main', 'java', 'com', 'avnivo', 'translator');
const activity = fs.readFileSync(path.join(main, 'MainActivity.kt'), 'utf8');
const recorder = fs.readFileSync(path.join(main, 'OfflinePcmRecorder.kt'), 'utf8');
const shipped = fs.readFileSync(path.join(root, 'android-app', 'app', 'src', 'main', 'assets', 'index.html'), 'utf8');

/** Extract a block body by balanced-brace scan so nested blocks do not truncate it. */
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

const startBody = functionBody(activity, '@JavascriptInterface fun start(session: Int = 0)');
const beginBody = functionBody(activity, 'private fun beginRecording(session: Int)');
const transcribeBody = functionBody(activity, 'private fun transcribeCaptured(');
const captureBody = functionBody(recorder, 'fun capture(maxSeconds: Int = 30');
const resultHandler = functionBody(shipped, 'window.AvnivoOfflineResult=function(text,session)');
const processingHandler = functionBody(shipped, 'window.AvnivoOfflineProcessing=function(message,session)');
const beginHold = functionBody(shipped, 'function beginHold()');
const releaseHold = functionBody(shipped, 'function releaseHold(event)');

test('1. the first press opens its own capture session', () => {
  // One session number, owned by the page and echoed by the bridge, lets a result be matched to
  // the press that made it.
  assert.match(startBody, /val current = if \(session > 0\) session else \+\+sttSession/,
    'the page session must be adopted, with a fallback id');
  assert.match(startBody, /openCaptureSession = current/, 'the open capture must record its session');
  assert.match(startBody, /beginRecording\(current\)/, 'capture must be given the session id');
});

test('1b. a release from an older press cannot cut a newer one', () => {
  const stopBody = functionBody(activity, '@JavascriptInterface fun stop(session: Int = 0)');
  assert.match(stopBody, /if \(session > 0 && openCaptureSession != 0 && session != openCaptureSession\) return/);
});

test('1c. a finished capture only clears state it still owns', () => {
  assert.match(beginBody, /if \(recorder === capture\) recorder = null/);
  assert.match(beginBody, /if \(openCaptureSession == session\)/);
});

test('2. a press is not blocked by the previous recording being transcribed', () => {
  // The old guard was `if (recording || busy) return`, and `busy` stayed set while decoding ran.
  // That is why the second press was needed to see the first result.
  assert.match(startBody, /val open = openCaptureSession/, 'the open capture must be read first');
  assert.match(startBody, /if \(open != 0\)/, 'only an open capture may refuse a press');
  assert.doesNotMatch(
    startBody,
    /if \(recording \|\| busy\)/,
    'a press must not be refused because a previous result is still being decoded'
  );
});

test('3. capture is not queued behind model loading', () => {
  // Capture and inference must not share the executor that downloads models.
  assert.match(activity, /private val captureWorker = Executors\.newSingleThreadExecutor\(\)/);
  assert.match(activity, /private val sttWorker = Executors\.newSingleThreadExecutor\(\)/);
  assert.match(beginBody, /captureWorker\.execute/, 'capture must run on its own worker');
  assert.match(transcribeBody, /sttWorker\.execute/, 'inference must run on its own worker');
  assert.doesNotMatch(beginBody, /worker\.execute/, 'capture must never wait on the model worker');
});

test('4. a release before the microphone opens still yields audio', () => {
  // The first press is the shortest one. Without this floor it returned an empty array, which the
  // page then reported as "no speech" — indistinguishable from a broken recognizer.
  assert.match(captureBody, /shouldContinue\(stopRequested, count, limit\)/,
    'capture must use the documented hold condition');
  assert.match(recorder, /!stopRequested \|\| sampleCount < MIN_HOLD_SAMPLES/,
    'a release must not end capture before the floor');
  assert.match(recorder, /const val MIN_HOLD_SAMPLES = 4800/, 'the floor must be the documented 0.3 s');
});

test('5. processing is announced before decoding starts', () => {
  // The indicator must reflect the decoding stage, and it must arrive before the slow work.
  const capturedAt = beginBody.indexOf('AvnivoOfflineProcessing');
  const decodeAt = beginBody.indexOf('transcribeCaptured(session, samples)');
  assert.ok(capturedAt !== -1, 'capture must announce the processing stage');
  assert.ok(capturedAt < decodeAt, 'processing must be announced before inference begins');
  assert.match(processingHandler, /offlineStateName\('loading'\)/, 'the indicator must show processing');
});

test('6. the release keeps the press alive while decoding', () => {
  // Clearing hold-voice without entering processing is what made a correct press look dead.
  assert.match(releaseHold, /offlineStateName\('loading'\)/, 'release must enter the processing state');
  assert.match(releaseHold, /offlineProcessingSession=offlineSession/, 'the stage must belong to this press');
  assert.doesNotMatch(
    releaseHold,
    /if\(wasHold\)\{[^}]*finishOfflineHold\(\);\s*offlineProcessingSession=0/,
    'release must not clear the press before decoding'
  );
});

test('7. a stale result cannot overwrite a newer press', () => {
  // This is the reported "second press shows the first transcript" defect.
  assert.match(resultHandler, /if\(session!==undefined&&Number\(session\)!==offlineSession\)return/);
  assert.match(beginHold, /const session=\+\+offlineSession/, 'every press must take a new page session');
});

test('8. a stale amplitude cannot move the current waveform', () => {
  const levelHandler = functionBody(shipped, 'window.AvnivoOfflineLevel=function(value,session)');
  assert.match(levelHandler, /Number\(session\)!==offlineSession\)return/);
});

test('9. native callbacks are stamped with the session', () => {
  const notifyBody = functionBody(activity, 'private fun notifyJs(');
  assert.match(activity, /private fun notifyJs\(function: String, value: String, session: Int = 0\)/, 'the bridge must accept a session');
  assert.match(notifyBody, /window\.\$function\(\$escaped,\$session\)/, 'the session must reach the page');
  assert.match(transcribeBody, /notifyJs\("AvnivoOfflineResult", text, session\)/, 'results must carry it');
});

test('10. an unusable recording reports its measured reason', () => {
  // A short or blank recording must say why, instead of returning an empty string that reads as
  // a silent failure. Both branches log a session-tagged line for logcat.
  assert.match(transcribeBody, /samples\.size < MIN_SAMPLES/, 'short audio must be detected');
  assert.match(transcribeBody, /logStt\(session, "too-short"/, 'the reason must be logged');
  assert.match(transcribeBody, /text\.isBlank\(\)/, 'a blank transcript must be detected');
  assert.match(activity, /Log\.i\("AvnivoOfflineStt", "session=\$session stage=\$stage \$detail"\)/);
});

test('11. the recognizer is reused instead of rebuilt on every press', () => {
  // Rebuilding a NeMo recognizer costs seconds; paying it per press made the first result late.
  assert.match(activity, /private var uzEngine: OfflineSpeechEngine\? = null/);
  const verifyBody = functionBody(activity, 'private fun verifyEngine()');
  assert.match(verifyBody, /val engine = uzEngine \?: OfflineSpeechEngine/, 'a verified engine must be kept');
  assert.match(transcribeBody, /uzEngine \?: OfflineSpeechEngine/, 'recognition must reuse it');
  assert.match(activity, /uzEngine\?\.close\(\);\s*uzEngine = null/, 'a failed engine must be dropped');
});

test('12. the session fields exist so the contract compiles', () => {
  assert.match(activity, /@Volatile private var sttSession = 0/);
  assert.match(activity, /@Volatile private var openCaptureSession = 0/);
});
