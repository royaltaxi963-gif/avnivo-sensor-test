/*
 * Node test: the online microphone path must ask for permission and must never fail silently.
 *
 * The reported defect was that pressing the microphone did nothing on a real phone: no system
 * permission dialog, no OS microphone indicator, no waveform, no processing animation and no
 * transcript. All five symptoms came from one place — the WebView audio capture request was
 * denied outright because nothing ever asked Android for RECORD_AUDIO, and every error on that
 * path was swallowed. These assertions pin the fix so it cannot regress into silence again.
 */
const { test } = require('node:test');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const path = require('node:path');

const root = path.join(__dirname, '..');
const activityPath = path.join(
  root, 'android-app', 'app', 'src', 'main', 'java', 'com', 'avnivo', 'translator', 'MainActivity.kt'
);
const shippedPath = path.join(root, 'android-app', 'app', 'src', 'main', 'assets', 'index.html');

const policyPath = path.join(
  root, 'android-app', 'app', 'src', 'main', 'java', 'com', 'avnivo', 'translator', 'MicPermissionPolicy.kt'
);

const activity = fs.readFileSync(activityPath, 'utf8');
const shipped = fs.readFileSync(shippedPath, 'utf8');
const policy = fs.readFileSync(policyPath, 'utf8');

/** Extract a function body by balanced-brace scan, so nested blocks do not truncate it. */
function functionBody(source, signature) {
  const start = source.indexOf(signature);
  assert.ok(start !== -1, `expected ${signature} to exist`);
  const open = source.indexOf('{', start);
  assert.ok(open !== -1, `expected ${signature} to have a block body`);
  let depth = 0;
  for (let i = open; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1;
    else if (source[i] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(open + 1, i);
    }
  }
  throw new Error(`unbalanced braces after ${signature}`);
}

test('the manifest declares RECORD_AUDIO', () => {
  const manifest = fs.readFileSync(
    path.join(root, 'android-app', 'app', 'src', 'main', 'AndroidManifest.xml'), 'utf8'
  );
  assert.match(manifest, /android\.permission\.RECORD_AUDIO/);
});

test('a WebView capture request is routed through the native permission gate', () => {
  const body = functionBody(activity, 'override fun onPermissionRequest');
  assert.match(body, /handleWebPermissionRequest/, 'the request must reach the permission gate');
});

test('a missing permission is requested, never silently denied', () => {
  const body = functionBody(activity, 'private fun handleWebPermissionRequest');
  assert.match(body, /checkSelfPermission\(Manifest\.permission\.RECORD_AUDIO\)/, 'must check the current grant');
  assert.match(body, /requestPermissions\(\s*arrayOf\(Manifest\.permission\.RECORD_AUDIO\)/, 'must ask Android');
  assert.match(body, /pendingWebAudioRequest = request/, 'must remember the request across the dialog');
});

test('the grant is only given to this app own page', () => {
  const body = functionBody(activity, 'private fun handleWebPermissionRequest');
  assert.match(body, /MicPermissionPolicy\.grantableResources/, 'origin must be validated before granting');
  assert.match(policy, /scheme == "file"/, 'only the bundled asset page is trusted');
  assert.match(policy, /android_asset/, 'the asset host must be allowed');
  assert.ok(
    !/scheme == "https"/.test(policy) && !/scheme == "http"/.test(policy),
    'no remote scheme may be trusted'
  );
});

test('the pending request is answered when the dialog closes', () => {
  const body = functionBody(activity, 'override fun onRequestPermissionsResult');
  assert.match(body, /pending\?\.grant/, 'a granted permission must complete the WebView request');
  assert.match(body, /pending\?\.deny/, 'a refused permission must fail the WebView request');
  assert.match(body, /AvnivoOnlineMicError/, 'a refusal must be reported to the page');
});

test('only audio capture is ever granted, never a blanket grant', () => {
  const body = functionBody(activity, 'private fun handleWebPermissionRequest');
  const grants = [...body.matchAll(/\.grant\(/g)];
  assert.ok(grants.length > 0, 'expected at least one grant');
  assert.ok(
    body.includes('request.grant(allowed.toTypedArray())'),
    'every grant must come from the narrowed policy result'
  );
  assert.ok(
    !/request\.grant\(request\.resources/.test(body),
    'the raw request resources must never be granted wholesale'
  );
  // The policy itself must narrow any request down to the audio resource.
  const decide = functionBody(policy, 'fun grantableResources');
  assert.match(decide, /listOf\(AUDIO_CAPTURE\)/, 'the policy must return audio capture only');
});

test('online mode no longer swallows hints', () => {
  const body = functionBody(shipped, 'function hint(message)');
  assert.doesNotMatch(body, /avnivoMode==='online'\)\s*\{\s*voiceHint\.hidden=true;\s*return/,
    'online mode must not hide the message and return early');
  assert.match(body, /voiceHint\.hidden=!message/, 'the message must be rendered');
});

test('a capture failure names its cause instead of returning silently', () => {
  const body = functionBody(shipped, 'async function startVoice()');
  assert.match(body, /NotAllowedError/, 'permission refusal must be named');
  assert.match(body, /NotFoundError/, 'a missing device must be named');
  assert.match(body, /hint\(message\)/, 'the cause must be shown to the user');
  assert.match(body, /shell\.classList\.remove\('hold-voice'\)/, 'the gesture visual must be restored');
});

test('a missing capture API is reported, not ignored', () => {
  const body = functionBody(shipped, 'async function startVoice()');
  assert.doesNotMatch(body, /getUserMedia\)\{return\}/, 'the old silent return must be gone');
  assert.match(body, /getUserMedia\)\{hint\(/, 'the user must be told the API is unavailable');
});

test('a Chirp 2 failure reaches the user', () => {
  const body = functionBody(shipped, 'async function sendOnlineAudio(');
  assert.match(body, /hint\('Ovozni matnga aylantirish xatosi/, 'a backend failure must be shown');
  assert.match(body, /AbortError/, 'a timeout or cancellation must be distinguished');
});

test('the native online-mic callbacks exist for the bridge to call', () => {
  assert.match(shipped, /window\.AvnivoOnlineMicError=function/, 'the denial callback must exist');
  assert.match(shipped, /window\.AvnivoOnlineMicGranted=function/, 'the grant callback must exist');
});

test('the waveform still reads real microphone amplitude', () => {
  const body = functionBody(shipped, 'function draw()');
  assert.match(body, /getByteTimeDomainData\(samples\)/, 'the analyser must supply real samples');
  assert.match(body, /Math\.sqrt\(sum\/samples\.length\)/, 'amplitude must come from the RMS of those samples');
});

test('the recording animation still has all four documented states', () => {
  for (const state of ['recording', 'loading', 'done', 'error']) {
    assert.ok(shipped.includes(`onlineState('${state}')`) || shipped.includes(`dataset.state='${state}'`),
      `state ${state} must be reachable`);
  }
  assert.match(shipped, /online-stt-status\[data-state=recording\]/, 'recording styling must exist');
  assert.match(shipped, /online-stt-status\[data-state=loading\]/, 'loading styling must exist');
});

test('no API key or token is embedded in the shipped asset', () => {
  assert.doesNotMatch(shipped, /AIza[0-9A-Za-z_-]{20,}/, 'no Google API key');
  assert.doesNotMatch(shipped, /Bearer\s+[A-Za-z0-9._-]{20,}/, 'no bearer token');
});
