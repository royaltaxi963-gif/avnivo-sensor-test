/*
 * Node tests: Whisper Small Q8 (multilingual-stt / whisper-small-q8) card stability.
 *
 * The S23 Ultra report had four symptoms on this one card: a percentage that appeared but never
 * moved, a false "Faol" before the model was loaded, "Faol" disappearing back into "Yuklash", and
 * an unstable settings state. They came from the card, not the download source: the native bridge
 * was already the active path, but the page collapsed INITIALIZING into VERIFYING, drew an
 * installed model with the download icon, and the manager demoted a live engine on every page load.
 *
 * These assertions pin the spec, the native path, the state mapping and the download/verification
 * contract without touching the online or other offline modules.
 */
const { test } = require('node:test');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const path = require('node:path');

const root = path.join(__dirname, '..');
const main = path.join(root, 'android-app', 'app', 'src', 'main', 'java', 'com', 'avnivo', 'translator');
const manager = fs.readFileSync(path.join(main, 'OfflineModuleManager.kt'), 'utf8');
const downloader = fs.readFileSync(path.join(main, 'VerifiedModelDownloader.kt'), 'utf8');
const specs = fs.readFileSync(path.join(main, 'OfflineModelSpecs.kt'), 'utf8');
const states = fs.readFileSync(path.join(main, 'OfflineModuleState.kt'), 'utf8');
const engine = fs.readFileSync(path.join(main, 'WhisperEngine.kt'), 'utf8');
const activity = fs.readFileSync(path.join(main, 'MainActivity.kt'), 'utf8');
const jni = fs.readFileSync(path.join(root, 'android-app', 'app', 'src', 'main', 'cpp', 'avnivo_whisper_jni.cpp'), 'utf8');
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

test('1. the spec is the exact 264,464,607-byte whisper-small-q8 artifact', () => {
  assert.match(specs, /id = "whisper-small-q8"/);
  assert.match(specs, /264464607L to "49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f"/);
  assert.match(specs, /multilingual-stt\/whisper-small-q8_0\/ggml-small-q8_0\.bin/);
});

test('2. the APK uses the native module bridge, not the JS installer', () => {
  // The click handler checks AvnivoOfflineModules first and only falls back to the JS installer.
  const bridgeAt = shipped.indexOf('bridge.download(module.installerId)');
  const fallbackAt = shipped.indexOf('const installer=window.avnivoSafeOfflineInstaller');
  assert.ok(bridgeAt !== -1, 'the native download call must exist');
  assert.ok(fallbackAt !== -1, 'the JS fallback must exist');
  assert.ok(bridgeAt < fallbackAt, 'the native bridge must be tried before the JS installer');
  assert.match(activity, /addJavascriptInterface\(Modules\(\), "AvnivoOfflineModules"\)/);
});

test('3. INITIALIZING has its own card state, not VERIFYING', () => {
  const map = shipped.match(/const offlineStateByInstaller=\{([^}]*)\}/);
  assert.ok(map, 'the installer->card state map must exist');
  assert.match(map[1], /initializing:'initializing'/);
  assert.doesNotMatch(map[1], /initializing:'verifying'/);
});

test('4. an installed model is drawn as verified, not as a download', () => {
  const body = functionBody(shipped, 'function updateOfflineModule(');
  assert.match(body, /btn\.classList\.toggle\('is-verified',downloaded\|\|installed\|\|initializing\)/);
  assert.match(body, /offlineSvg\(ready\|\|downloaded\|\|installed\|\|initializing\?'check':'download'\)/);
  assert.match(body, /Model o‘rnatilgan — faollashtirish uchun bosing/);
  assert.match(body, /btn\.classList\.toggle\('is-ready',ready\)/);
});

test('5. INITIALIZING hides the download bar and disables the button', () => {
  const body = functionBody(shipped, 'function updateOfflineModule(');
  assert.match(body, /btn\.disabled=state==='downloading'\|\|state==='verifying'\|\|initializing/);
  assert.match(body, /progressEl\.hidden=!\(state==='downloading'\|\|state==='verifying'\)\|\|!shownProgress/);
  assert.match(body, /state==='not_installed'\|\|installed\|\|initializing\|\|ready\|\|state==='error'/);
});

test('6. clicking an installed module does not fake a 0% download', () => {
  assert.match(shipped, /const alreadyInstalled=offlineState\.get\(module\.id\)==='installed'/);
  assert.match(shipped, /alreadyInstalled\?'initializing':'downloading'/);
  assert.match(shipped, /alreadyInstalled\?null:\{downloadedBytes:0,totalBytes:module\.bytes\}/);
  assert.match(shipped, /Model o‘rnatilgan\. Dvigatel yuklanmoqda/);
});

test('7. startup INSTALLED requires a SHA-256 stamp, not just a file length', () => {
  assert.match(manager, /ModelIntegrity\.isStamped\(directory, spec\)/);
  assert.match(manager, /OfflineModuleState\.VERIFYING/);
  const isInstalled = functionBody(manager, 'fun isInstalled(id: String)');
  assert.match(isInstalled, /module\.verified \|\| ModelIntegrity\.isStamped\(directory, module\.spec\)/);
});

test('8. reactivation never demotes a live engine', () => {
  const reactivate = functionBody(manager, 'fun reactivateInstalled()');
  assert.match(reactivate, /module\.state\.isOperational && module\.engine != null/);
  assert.match(reactivate, /if \(module\.state == OfflineModuleState\.DOWNLOADING\) continue/);
});

test('9. a corrupt installed file is deleted and fetched again in the same press', () => {
  const download = functionBody(manager, 'fun download(id: String)');
  assert.match(download, /module\.spec\.files\.keys\.forEach \{ File\(directory, it\)\.delete\(\) \}/);
  assert.match(download, /transition\(module, OfflineModuleState\.NOT_INSTALLED\)/);
  assert.match(download, /downloaderFactory\(directory, module\.spec\.id, module\.spec\.files\)/);
  assert.match(download, /if \(!verified\)/);
});

test('10. progress is real bytes on the channel the card listens to', () => {
  assert.match(manager, /notify\("AvnivoOfflineModuleProgress", "\$id\|\$done\/\$total"\)/);
  assert.match(downloader, /val totalBytes = expected\.values\.sumOf \{ it\.first \}/);
  assert.match(downloader, /bytesProgress\(base \+ done, totalBytes\)/);
  assert.match(downloader, /c\.setRequestProperty\("Range", "bytes=\$existing-"\)/);
  assert.match(downloader, /c\.getHeaderField\("Content-Range"\)/);
  assert.match(downloader, /MAX_ATTEMPTS = 3/);
});

test('11. startup and re-verification transitions are legal', () => {
  assert.match(states, /NOT_INSTALLED -> next in setOf\(DOWNLOADING, VERIFYING/);
  assert.match(states, /INSTALLED -> next in setOf\(VERIFYING/);
  assert.match(states, /ERROR -> next in setOf\(DOWNLOADING, VERIFYING/);
});

test('12. the card is reconciled on resume, not only on first load', () => {
  assert.match(shipped, /function syncInstalledModules\(\)/);
  assert.match(shipped, /visibilitychange[\s\S]*syncInstalledModules\(\)/);
  assert.match(shipped, /pageshow[\s\S]*syncInstalledModules\(\)/);
});

test('13. READY clears a stale reason and ERROR renders its own reason', () => {
  const ready = functionBody(shipped, 'window.AvnivoOfflineModuleReady=function(installerId)');
  assert.match(ready, /note\.hidden=true/);
  const error = functionBody(shipped, 'window.AvnivoOfflineModuleError=function(raw)');
  assert.match(error, /note\.textContent=parts\[1\]/);
});

test('14. READY still requires the real whisper.cpp load and smoke inference', () => {
  assert.match(engine, /nativeInit\(modelFile\.absolutePath\)/);
  assert.match(engine, /fun smokeTest\(\): String/);
  // The smoke test feeds one second of audio through the real decoder. It calls decode() directly
  // because transcribe() now answers silence without decoding.
  assert.match(engine, /decode\(FloatArray\(SAMPLE_RATE\), SAMPLE_RATE, null\)/);
  assert.match(manager, /loadVerified\(WhisperEngine\(model, language = "auto"\)\)/);
  assert.match(jni, /whisper_init_from_file_with_params/);
  assert.match(jni, /whisper_full\(session->ctx/);
  const readySites = [...manager.matchAll(/transition\(module, OfflineModuleState\.READY\)/g)];
  assert.equal(readySites.length, 1, 'only activate() may promote a module to READY');
});
