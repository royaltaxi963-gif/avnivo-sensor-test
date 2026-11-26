/*
 * Node tests: the online capture lifecycle must not leak the microphone.
 *
 * The phone reported "microphone is busy in another app" with RECORD_AUDIO granted. Two causes
 * were found in the page: a new getUserMedia call was made while the previous stream was still
 * open, and the request asked for a raw stream by disabling echo cancellation, noise suppression
 * and auto gain. Many devices can satisfy neither, and both surface as NotReadableError — the
 * same message as a genuinely busy device, which is why the real cause was invisible.
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

const startVoice = functionBody(shipped, 'async function startVoice()');
const release = functionBody(shipped, 'async function releaseOnlineCapture()');
const stopVoice = functionBody(shipped, 'async function stopVoice()');

test('1. a previous stream is released before a new one is opened', () => {
  assert.ok(shipped.includes('async function releaseOnlineCapture()'), 'a serialized teardown must exist');
  const releaseAt = startVoice.indexOf('await releaseOnlineCapture()');
  const captureAt = startVoice.indexOf('getUserMedia(');
  assert.ok(releaseAt !== -1, 'startVoice must release first');
  assert.ok(releaseAt < captureAt, 'the release must happen before getUserMedia');
});

test('2. every track and the audio graph are closed', () => {
  assert.match(release, /mediaStream\.getTracks\(\)\.forEach/, 'tracks must be stopped');
  assert.match(release, /track\.stop\(\)/, 'each track must be stopped');
  assert.match(release, /mediaStream=null/, 'the stream must be dropped');
  assert.match(release, /await context\.close\(\)/, 'the AudioContext must be closed');
  assert.match(release, /audioContext=null/, 'the context reference must be dropped');
});

test('3. the capture token is taken after the teardown, not before', () => {
  // Releasing bumps the token; taking the token first would make every press cancel itself.
  const releaseAt = startVoice.indexOf('await releaseOnlineCapture()');
  const tokenAt = startVoice.indexOf('const token=++requestToken');
  assert.ok(tokenAt !== -1, 'startVoice must take a token');
  assert.ok(releaseAt < tokenAt, 'the token must be taken after the teardown');
});

test('4. a raw stream is no longer requested', () => {
  // These constraints are the documented cause of NotReadableError on devices that cannot deliver
  // an unprocessed stream. Default processing is the compatible request.
  assert.doesNotMatch(startVoice, /echoCancellation:\s*false/, 'echoCancellation:false must not be requested');
  assert.doesNotMatch(startVoice, /noiseSuppression:\s*false/, 'noiseSuppression:false must not be requested');
  assert.doesNotMatch(startVoice, /autoGainControl:\s*false/, 'autoGainControl:false must not be requested');
  assert.match(startVoice, /getUserMedia\(\{audio:true\}\)/, 'the plain audio request must be used');
});

test('5. a failed capture releases the device instead of holding it', () => {
  const catchAt = startVoice.indexOf("name==='NotReadableError'");
  assert.ok(catchAt !== -1, 'NotReadableError must be named, not hidden');
  const releaseAfter = startVoice.indexOf('await releaseOnlineCapture()', catchAt);
  assert.ok(releaseAfter > catchAt, 'the failure path must release the microphone');
});

test('6. NotReadableError keeps its real meaning', () => {
  // The message must not be softened into a fake success or a vague error.
  assert.match(shipped, /NotReadableError'\?'Mikrofon boshqa ilova tomonidan band'/);
});

test('7. stopping a recording also releases the device', () => {
  assert.match(stopVoice, /mediaStream\.getTracks\(\)\.forEach/, 'stop must release tracks');
  assert.match(stopVoice, /await context\.close\(\)/, 'stop must close the context');
});

test('8. the processing indicator is driven by the real request state', () => {
  // 'loading' is the existing indicator state for a request in flight; it must be set when the
  // recorder stops, not invented as a new visual.
  assert.match(shipped, /function onlineState\(state\)/);
  assert.match(shipped, /onlineState\('loading'\)/, 'the loading stage must be reachable');
});
