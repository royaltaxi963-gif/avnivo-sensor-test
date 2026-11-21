/*
 * Node test: speech recognition must never send, translate or speak on its own.
 *
 * The agreed flow is speak -> text lands in the composer -> the user reviews it -> the user
 * presses the arrow. Auto-sending on STT completion would silently post half-recognised text
 * to the chat and fire a translation the user never asked for. This test reads the shipped
 * asset and fails if a recognition end/result handler ever grows a call into the send path.
 */
const { test } = require('node:test');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const path = require('node:path');

const shippedPath = path.join(__dirname, '..', 'android-app', 'app', 'src', 'main', 'assets', 'index.html');
const shipped = fs.readFileSync(shippedPath, 'utf8');

/** Side effects that must only ever start from a user gesture. */
const USER_GESTURE_ONLY = ['sendMessage', 'translateOfflineText', 'translateOnlineText', 'sendOnlineAudio'];

/**
 * Extract the body of a recognizer callback, e.g. `recognition.onend=()=>{ ... };`.
 * Balanced-brace scan rather than a regex, so nested blocks do not truncate the body.
 */
function callbackBody(source, name) {
  const start = source.indexOf(`${name}=`);
  assert.ok(start !== -1, `expected ${name} to exist in the shipped asset`);
  const open = source.indexOf('{', start);
  assert.ok(open !== -1, `expected ${name} to have a block body`);
  let depth = 0;
  for (let i = open; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1;
    else if (source[i] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(open, i + 1);
    }
  }
  throw new Error(`unbalanced braces in ${name}`);
}

test('speech recognition end handler never sends, translates or speaks', () => {
  const body = callbackBody(shipped, 'recognition.onend');
  for (const fn of USER_GESTURE_ONLY) {
    assert.ok(
      !new RegExp(`\\b${fn}\\s*\\(`).test(body),
      `recognition.onend must not call ${fn}() — that is the manual-send contract`
    );
  }
});

test('speech recognition result handler only collects text', () => {
  const body = callbackBody(shipped, 'recognition.onresult');
  for (const fn of USER_GESTURE_ONLY) {
    assert.ok(
      !new RegExp(`\\b${fn}\\s*\\(`).test(body),
      `recognition.onresult must not call ${fn}() — partial results are not sent`
    );
  }
});

test('the recognizer end handler writes the transcript into the composer', () => {
  const body = callbackBody(shipped, 'recognition.onend');
  assert.ok(
    /input\.value\s*=\s*appendTranscript\(/.test(body),
    'recognition.onend must place the transcript in the input field for review'
  );
});

/** Balanced-brace body starting at the first `{` after `from`. */
function blockAfter(source, from) {
  const open = source.indexOf('{', from);
  if (open === -1) return '';
  let depth = 0;
  for (let i = open; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1;
    else if (source[i] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(open, i + 1);
    }
  }
  return '';
}

test('sendMessage is wired to an explicit user gesture', () => {
  // A send may start from Enter, from the arrow button, or from a short mic tap. All three are
  // deliberate user actions. What must never happen is a send triggered by recognition itself.
  const gestureHandlers = ['keydown', 'pointerup', 'click', 'touchend'];
  let reachable = false;
  for (const evt of gestureHandlers) {
    const marker = `addEventListener('${evt}'`;
    let idx = -1;
    while ((idx = shipped.indexOf(marker, idx + 1)) !== -1) {
      if (blockAfter(shipped, idx).includes('sendMessage')) {
        reachable = true;
        break;
      }
    }
    if (reachable) break;
  }
  assert.ok(
    reachable,
    `sendMessage() must be reachable from one of: ${gestureHandlers.join(', ')}`
  );
});

test('the mic tap-to-send branch cannot fire while a hold recording is finishing', () => {
  // releaseHold sends only in the `else` branch, i.e. when the press was a short tap rather than
  // a recording. If the send ever moved into the hold branch, releasing the mic after dictation
  // would post the transcript without the user reviewing it.
  const start = shipped.indexOf('const wasHold=');
  assert.ok(start !== -1, 'expected releaseHold to compute wasHold');
  const body = shipped.slice(start, shipped.indexOf('mic.addEventListener', start));
  const holdBranch = body.indexOf('if(wasHold)');
  assert.ok(holdBranch !== -1, 'expected a wasHold branch');
  const sendIdx = body.indexOf('sendMessage()');
  assert.ok(sendIdx !== -1, 'expected the tap-to-send branch to exist');
  assert.ok(
    sendIdx > holdBranch,
    'sendMessage() must not run in the wasHold (recording) branch'
  );
});
