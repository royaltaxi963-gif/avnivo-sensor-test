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
const controller = fs.readFileSync(path.join(main, 'UzbekSttController.kt'), 'utf8');
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

const startBody = functionBody(activity, 'private fun startForLanguage(session: Int, sourceLanguage: String)');
const beginBody = functionBody(activity, 'private fun beginRecording(session: Int)');
const transcribeBody = functionBody(activity, 'private fun transcribeCaptured(');
const captureBody = functionBody(recorder, 'fun capture(maxSeconds: Int = 30');
const beginCaptureBody = functionBody(controller, 'fun beginCapture(requested: Int): Int');
const endCaptureBody = functionBody(controller, 'fun endCapture(session: Int)');
const ownsCaptureBody = functionBody(controller, 'fun ownsCapture(session: Int): Boolean');
const ensureReadyBody = functionBody(controller, 'fun ensureReady(session: Int = 0): SttRecognizer');
const transcribeControllerBody = functionBody(controller, 'fun transcribe(session: Int, samples: FloatArray): String');
const completeBody = functionBody(controller, 'fun complete(session: Int)');
const failBody = functionBody(controller, 'fun fail(session: Int, message: String)');
const closeRecognizerBody = functionBody(controller, 'private fun closeRecognizerLocked()');
const resultHandler = functionBody(shipped, 'window.AvnivoOfflineResult=function(text,session)');
const processingHandler = functionBody(shipped, 'window.AvnivoOfflineProcessing=function(message,session)');
const beginHold = functionBody(shipped, 'function beginHold()');
const releaseHold = functionBody(shipped, 'function releaseHold(event)');

test('1. the first press opens its own capture session', () => {
  assert.match(activity, /@JavascriptInterface fun start\(session: Int = 0\) =\s*startForLanguage/,
    'the legacy bridge entry point must delegate to the language-aware path');
  // One session number, owned by the page and adopted by the controller, lets a result be matched
  // to the press that made it.
  assert.match(beginCaptureBody, /val current = if \(requested > 0\) requested else lastSession \+ 1/,
    'the page session must be adopted, with a fallback id');
  assert.match(beginCaptureBody, /openSession = current/, 'the open capture must record its session');
  assert.match(startBody, /sttController\.beginCapture\(opened\.session\)/, 'the bridge must delegate to the controller');
  assert.match(startBody, /beginRecording\(current\)/, 'capture must be given the session id');
});

test('1b. a release from an older press cannot cut a newer one', () => {
  assert.match(ownsCaptureBody, /session <= 0 \|\| session == openSession/);
  const stopBody = functionBody(activity, '@JavascriptInterface fun stop(session: Int = 0)');
  assert.match(stopBody, /if \(!sttController\.ownsCapture\(session\)\) return/);
});

test('1c. a finished capture only clears state it still owns', () => {
  assert.match(endCaptureBody, /if \(openSession != session\) return/,
    'an old capture must not clear a newer capture');
  assert.match(endCaptureBody, /openSession = 0/);
  assert.match(beginBody, /sttController\.endCapture\(session\)/);
});

test('1d. release ends capture and enters processing before prepare/decode', () => {
  // The S23 Ultra report: the Uzbek card stayed on "Ovoz yozilmoqda..." while the engine was
  // being reloaded after Whisper Large, so the next press hit the cross-engine guard forever.
  assert.match(endCaptureBody, /transcribingSession = session/,
    'the released session must own the processing slot');
  assert.match(endCaptureBody, /setState\(OfflineModuleState\.TRANSCRIBING/,
    'the card must move to processing as soon as the microphone closes');
  const processingAt = transcribeControllerBody.indexOf('setState(OfflineModuleState.TRANSCRIBING');
  const prepareAt = transcribeControllerBody.indexOf('ensureReady(session)');
  assert.ok(processingAt !== -1 && prepareAt !== -1 && processingAt < prepareAt,
    'processing must be published before a slow ensureReady/prepare');
  assert.match(transcribeControllerBody, /transcribingSession = session/,
    'the decode must claim its session before preparing the recognizer');
});

test('1e. a microphone release stops AudioRecord, not just a flag', () => {
  const stopBody = functionBody(recorder, 'fun stop()');
  assert.match(stopBody, /recordingState == AudioRecord\.RECORDSTATE_RECORDING\) audio\.stop\(\)/,
    'stop() must wake a blocked AudioRecord.read()');
  assert.match(captureBody, /if \(stopRequested\) break/,
    'a read that fails because of release must end normally');
  assert.match(activity, /CAPTURE_RELEASE_TIMEOUT_MS = 5_000L/,
    'a release watchdog must settle a capture that never returns');
  assert.match(activity, /capture-timeout/, 'the watchdog must leave a traceable log line');
  assert.match(activity, /capture\?\.abort\(\)/, 'the watchdog must force-stop the stuck recorder');
  assert.match(recorder, /fun abort\(\)/, 'the recorder must expose an emergency stop');
});

test('1f. a rejection is stamped with the new press session', () => {
  assert.match(
    startBody,
    /notifyJs\("AvnivoOfflineRejected", "Yozib olish allaqachon davom etmoqda\.", requested\)/,
    'the page must receive a rejection it can act on'
  );
  assert.match(startBody, /if \(recorder == null\)/,
    'a capture that ended without clearing the controller must be settled first');
  assert.match(startBody, /stale-capture/, 'the stale-session recovery must be traceable');
});

test('1g. the cross-engine guard is real and clears with the inference', () => {
  assert.match(
    startBody,
    /if \(transcribingEngine != null && transcribingEngine != route\.engine\)/,
    'two different engines must never decode at the same time'
  );
  assert.match(startBody, /previous-engine=\$\{transcribingEngine\?\.moduleId\}/,
    'a rejection must log which engine still owns the session');
  assert.match(transcribeBody, /if \(transcribingEngine == route\.engine\) transcribingEngine = null/,
    'the guard must be cleared on success and failure');
  assert.match(transcribeBody, /inference-end/, 'the end of inference must be traceable');
});

test('1h. an older completion cannot settle a newer processing session', () => {
  assert.match(completeBody, /if \(transcribingSession != 0\) return/,
    'an older complete() must not promote the newer session to READY');
  assert.match(failBody, /if \(transcribingSession != 0\) return/,
    'an older fail() must not overwrite the newer session');
  assert.match(endCaptureBody, /transcribingSession = session/,
    'each released capture must become the newest pending decode');
});

test('2. a press is not blocked by the previous recording being transcribed', () => {
  // The old guard was `if (recording || busy) return`, and `busy` stayed set while decoding ran.
  // That is why the second press was needed to see the first result.
  assert.match(startBody, /if \(sttController\.isRecording\)/, 'only an open capture may refuse a press');
  assert.doesNotMatch(startBody, /if \(recording \|\| busy\)/,
    'a press must not be refused because a previous result is still being decoded');
  assert.match(beginCaptureBody, /if \(openSession != 0\) return 0/,
    'a decode that is still running must not hold the capture slot');
  assert.match(transcribeControllerBody, /transcribingSession = session/);
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
  // Startup verification and a press both go through the controller's single accessor.
  assert.match(ensureReadyBody, /recognizer\?\.let \{ if \(recognizerVerified\) return it \}/,
    'an already verified decoder must be returned as is');
  assert.match(ensureReadyBody, /val engine = recognizer \?: recognizerFactory\(\)\.also \{ recognizer = it \}/,
    'the factory must run only when no decoder exists');
  const verifyBody = functionBody(activity, 'private fun verifyEngine()');
  assert.match(verifyBody, /sttController\.ensureReady\(\)/, 'verification must use the shared accessor');
  assert.match(transcribeBody, /sttController\.transcribe\(session, samples\)/, 'recognition must reuse it');
});

test('11b. engine preparation is idempotent and thread safe', () => {
  // The device reported "Recognizer already initialized": prepare() was called a second time on a
  // cached engine. It must be a no-op instead, and every entry point must be serialized.
  const engine = fs.readFileSync(path.join(main, 'OfflineSpeechEngine.kt'), 'utf8');
  const prepare = functionBody(engine, 'override fun prepare()');
  assert.match(prepare, /if \(recognizer != null\) return/, 'a second prepare must be a no-op');
  assert.doesNotMatch(prepare, /check\(recognizer == null\)/, 'the throwing guard must be gone');
  assert.match(engine, /@Synchronized\s*\n\s*override fun prepare\(\)/, 'prepare must be synchronized');
  assert.match(engine, /@Synchronized\s*\n\s*override fun transcribe\(/, 'decode must be synchronized');
  assert.match(engine, /@Synchronized\s*\n\s*override fun close\(\)/, 'close must be synchronized');
  assert.match(engine, /override val isPrepared/, 'callers need a non-throwing readiness check');

  const whisper = fs.readFileSync(path.join(main, 'WhisperEngine.kt'), 'utf8');
  const whisperPrepare = functionBody(whisper, 'fun prepare()');
  assert.match(whisperPrepare, /if \(handle != 0L\) return/, 'whisper prepare must be idempotent too');
  assert.doesNotMatch(whisperPrepare, /Recognizer already initialized/, 'the throwing guard must be gone');
});

test('12. the session fields exist so the contract compiles', () => {
  assert.match(controller, /@Volatile var lastSession = 0/);
  assert.match(controller, /@Volatile var openSession = 0/);
  assert.match(controller, /@Volatile var transcribingSession = 0/);
});

test('13. every native resource is released when the activity is destroyed', () => {
  // A leaked engine or executor outlives the activity and keeps the microphone and the native
  // recognizer alive after the user leaves, which is what produced "microphone busy" reports.
  const destroy = functionBody(activity, 'override fun onDestroy()');
  for (const call of [
    'recorder?.abort()',
    'ttsPlayer.close()',
    'ttsWorker.shutdown()',
    'moduleManager.close()',
    'sttController.close()',
    'captureWorker.shutdown()',
    'sttWorker.shutdown()',
    'worker.shutdown()',
    'web.destroy()',
  ]) {
    assert.ok(destroy.includes(call), `onDestroy must release: ${call}`);
  }
  assert.match(destroy, /pendingWebAudioRequest\?\.deny\(\)/, 'a pending grant must be answered');
});

test('14. a failed engine is dropped so the next press can retry cleanly', () => {
  assert.match(ensureReadyBody, /catch \(t: Throwable\)[\s\S]*closeRecognizerLocked\(\)/,
    'a failed prepare must close and drop the decoder');
  assert.match(closeRecognizerBody, /recognizer = null/);
  assert.match(closeRecognizerBody, /recognizerVerified = false/);
});
