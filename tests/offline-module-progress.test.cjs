/*
 * Node tests: the module settings cards must show real, moving download progress.
 *
 * Reported on a Samsung S23 Ultra: the NLLB card sat at 0% / 916.8 MB for the whole download, the
 * Whisper Large card stayed on "Tekshirilmoqda…", and the Uzbek engine reported
 * "Recognizer already initialized". Each of those was a contract mismatch between the native side
 * and the page, not a slow model:
 *
 *   1. the module manager emitted `AvnivoOfflineProgress`, but the cards only listen on
 *      `AvnivoOfflineModuleProgress`, so the payload was dropped and the bar never moved;
 *   2. progress was reported per file against that file's own size, so an 8-file model restarted
 *      the bar at 0% eight times instead of advancing across the whole model;
 *   3. initialization only caught Exception/UnsatisfiedLinkError, so an OutOfMemoryError inside the
 *      large model left the state machine in INITIALIZING — an eternal "Tekshirilmoqda…".
 *
 * These assertions pin both sides of that contract.
 */
const { test } = require('node:test');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const path = require('node:path');

const root = path.join(__dirname, '..');
const main = path.join(root, 'android-app', 'app', 'src', 'main', 'java', 'com', 'avnivo', 'translator');
const manager = fs.readFileSync(path.join(main, 'OfflineModuleManager.kt'), 'utf8');
const downloader = fs.readFileSync(path.join(main, 'VerifiedModelDownloader.kt'), 'utf8');
const shipped = fs.readFileSync(path.join(root, 'android-app', 'app', 'src', 'main', 'assets', 'index.html'), 'utf8');

/** Every `window.<name>=` the page defines, so a native emitter can be checked against it. */
function definedHandlers() {
  return new Set([...shipped.matchAll(/window\.(Avnivo[A-Za-z]+)\s*=/g)].map(m => m[1]));
}

test('1. the module progress emitter matches a handler the page defines', () => {
  // This is the exact defect: a payload on a channel nobody listens to.
  const handlers = definedHandlers();
  assert.ok(handlers.has('AvnivoOfflineModuleProgress'), 'the page must define the module progress handler');
  assert.match(
    manager,
    /notify\("AvnivoOfflineModuleProgress", "\$id\/?\$done\/\$total"\)|notify\("AvnivoOfflineModuleProgress", "\$id\|\$done\/\$total"\)/,
    'the manager must emit on that channel'
  );
  assert.doesNotMatch(
    manager,
    /notify\("AvnivoOfflineProgress"/,
    'the module manager must not emit on the Uzbek-only channel'
  );
});

test('2. progress advances across the whole model, not per file', () => {
  // An 8-file model must report one growing bar, so the totals are the sum of every file.
  assert.match(downloader, /val totalBytes = expected\.values\.sumOf \{ it\.first \}/, 'total must be the model total');
  assert.match(downloader, /bytesProgress\(base \+ done, totalBytes\)/, 'in-flight progress must add the files already done');
  assert.match(downloader, /completedBytes \+= spec\.first/, 'a finished file must count toward the model total');
});

test('3. an already verified file is not downloaded again', () => {
  assert.match(downloader, /private fun isVerifiedCopy\(/, 'the reuse check must exist');
  assert.match(downloader, /if \(isVerifiedCopy\(destination, spec\.first, spec\.second\)\)/, 'it must gate the transfer');
  assert.match(downloader, /progress\("Allaqachon yuklangan: \$name"\)/, 'reuse must be reported, not silent');
  assert.match(downloader, /sha256Of\(file\)\.equals\(hash, ignoreCase = true\)/, 'reuse must be proven by digest');
});

test('4. transient network faults retry with a bounded attempt count', () => {
  assert.match(downloader, /private const val MAX_ATTEMPTS = 3/, 'retries must be bounded');
  assert.match(downloader, /for \(attempt in 1\.\.MAX_ATTEMPTS\)/, 'the loop must use the bound');
  assert.match(downloader, /if \(attempt == MAX_ATTEMPTS \|\| !isRetryable\(e\)\) throw e/, 'it must stop on a permanent error');
  assert.match(downloader, /private fun isRetryable\(/, 'retryable faults must be classified');
  assert.match(downloader, /file\.length\(\) in 1 until size/, 'a partial file must survive a failed attempt');
});

test('5. an out-of-memory initialization settles the state instead of hanging', () => {
  // The eternal "Tekshirilmoqda…" was an OOM that no catch clause handled.
  const activate = manager.slice(manager.indexOf('private fun activate(module: Module)'));
  assert.match(activate, /catch \(e: OutOfMemoryError\)/, 'OOM must be caught explicitly');
  assert.match(activate, /catch \(e: Throwable\)/, 'any other VM failure must also settle the state');
  assert.match(activate, /transition\(module, OfflineModuleState\.ERROR\)/, 'the failure must reach a terminal state');
  assert.match(activate, /xotira yetarli emas/, 'the reason must be actionable');
});

test('6. a failed engine is released instead of leaking its native handle', () => {
  assert.match(manager, /private fun <T : Closeable> loadVerified\(engine: T\): T/, 'a shared loader must exist');
  assert.match(manager, /runCatching \{ engine\.close\(\) \}/, 'a failed prepare/smoke test must release the engine');
  assert.match(manager, /loadVerified\(WhisperEngine\(model, language = "auto"\)\)/, 'whisper must use it');
  assert.match(manager, /loadVerified\(NllbEngine\(directory\)\)/, 'nllb must use it');
  assert.match(manager, /loadVerified\(SileroTtsEngine\(model\)\)/, 'tts must use it');
});

test('7. only one engine is resident, and it is loaded lazily', () => {
  // Loading every installed model on page load exhausted memory and left every card reading
  // "dvigatel ulanmagan". The replacement is one engine at a time, restored from disk.
  assert.match(manager, /private fun unloadOthers\(keep: Module\)/, 'activation must release the previous engine');
  assert.match(manager, /fun unload\(id: String\)/, 'an engine must be unloadable without losing the model');
  assert.match(manager, /fun onTrimMemory\(\)/, 'memory pressure must release what is not in use');
  assert.match(manager, /private fun ensureLoaded\(id: String\)/, 'a request must reload an unloaded model');
  assert.match(manager, /activeModuleId\?\.let \{ id ->/, 'page load must restore exactly one engine');
  assert.doesNotMatch(manager, /initialize\(module, allowEvict/, 'the old load-everything path must be gone');
});

test('8. the card shows the native reason instead of a bare spinner', () => {
  assert.match(shipped, /offline-module-reason/, 'the card needs a reason element');
  assert.match(
    shipped,
    /if\(info\.reason&&\(info\.state==='installed'\|\|info\.state==='error'\)\)/,
    'a non-operational module must surface its reason'
  );
  assert.match(shipped, /note\.textContent=String\(info\.reason\)/, 'the reason must be rendered');
});

test('9. a malformed progress payload cannot move the bar', () => {
  const handler = shipped.slice(shipped.indexOf('window.AvnivoOfflineModuleProgress=function(raw)'));
  const body = handler.slice(0, handler.indexOf('};') + 2);
  assert.match(body, /!Number\.isFinite\(done\)\|\|!Number\.isFinite\(total\)/, 'both numbers must be finite');
  assert.match(body, /total<=0\)return/, 'a zero total must be rejected, not divided by');
});

test('10. status carries byte progress so a restart can restore the bar', () => {
  assert.match(manager, /put\("progress", progress \?: JSONObject\.NULL\)/, 'status must expose progress');
  assert.match(manager, /put\("downloadedBytes", module\.downloadedBytes\)/, 'downloaded bytes must be reported');
  assert.match(manager, /put\("totalBytes", module\.totalBytes\)/, 'the total must be reported');
});

test('11. ready is still unreachable without a real initialization', () => {
  // The fixes must not have opened a shortcut from a completed download to an active engine.
  assert.match(manager, /transition\(module, OfflineModuleState\.VERIFYING\)/);
  assert.match(manager, /transition\(module, OfflineModuleState\.INSTALLED\)/);
  assert.match(manager, /transition\(module, OfflineModuleState\.INITIALIZING\)/);
  const readySites = [...manager.matchAll(/transition\(module, OfflineModuleState\.READY\)/g)];
  assert.equal(readySites.length, 1, 'only initialize() may promote a module to READY');
});
