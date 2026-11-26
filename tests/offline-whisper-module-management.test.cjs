/*
 * Node tests: Whisper Small/Large selection, activation, deletion and routing contracts.
 *
 * These are static guards around the native lifecycle and the settings cards. Real inference is
 * covered by the Android instrumentation suite; this file makes the non-negotiable rules visible
 * in every standard CI run.
 */
const { test } = require('node:test');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const path = require('node:path');

const root = path.join(__dirname, '..');
const main = path.join(root, 'android-app', 'app', 'src', 'main', 'java', 'com', 'avnivo', 'translator');
const manager = fs.readFileSync(path.join(main, 'OfflineModuleManager.kt'), 'utf8');
const specs = fs.readFileSync(path.join(main, 'OfflineModelSpecs.kt'), 'utf8');
const router = fs.readFileSync(path.join(main, 'OfflineSttRouter.kt'), 'utf8');
const activity = fs.readFileSync(path.join(main, 'MainActivity.kt'), 'utf8');
const jni = fs.readFileSync(path.join(root, 'android-app', 'app', 'src', 'main', 'cpp', 'avnivo_whisper_jni.cpp'), 'utf8');
const shipped = fs.readFileSync(path.join(root, 'android-app', 'app', 'src', 'main', 'assets', 'index.html'), 'utf8');

function body(source, signature) {
  const start = source.indexOf(signature);
  assert.ok(start !== -1, `expected ${signature}`);
  const open = source.indexOf('{', start);
  assert.ok(open !== -1, `expected body for ${signature}`);
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

test('1. Whisper Large spec is the exact 574,041,195-byte q5_0 artifact', () => {
  assert.match(specs, /id = "whisper-large-v3-turbo"/);
  assert.match(specs, /574041195L to "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"/);
  assert.match(specs, /multilingual-stt\/whisper-large-v3-turbo-q5_0/);
});

test('2. activation is explicit and never starts a download', () => {
  const activate = body(manager, 'fun activate(id: String)');
  assert.match(activate, /worker\.execute \{ verifyAndActivate\(module\) \}/);
  assert.doesNotMatch(activate, /downloaderFactory|download\(/);
  const verify = body(manager, 'private fun verifyAndActivate(module: Module)');
  assert.match(verify, /integrityVerifier\(directory, module\.spec\)/);
  assert.match(verify, /activate\(module\)/);
});

test('3. only the two Whisper models exclude each other', () => {
  // The device report: switching NLLB on switched Whisper off, and the other way round, which
  // broke the speech -> translation -> speech chain. Only Small and Large are alternatives.
  const enable = body(manager, 'private fun enable(module: Module)');
  assert.match(enable, /peer\.spec\.exclusiveGroup != group/, 'only the shared group may be released');
  assert.match(enable, /rememberEnabled\(peer\.spec\.id, false\)/, 'the released peer must record the intent');
  assert.match(enable, /module\.enabled = true/);
  assert.doesNotMatch(manager, /unloadOthers/, 'a blanket unload is what switched unrelated modules off');
  assert.match(specs, /exclusiveGroup = WHISPER_GROUP/, 'the Whisper specs must share a group');
  const activate = body(manager, 'private fun activate(module: Module)');
  assert.match(activate, /enable\(module\)/);
  assert.match(activate, /makeRoomFor\(module\)/, 'memory, not the user, decides what is released');
});

test('4. the selected Whisper choice is persisted and restored', () => {
  assert.match(manager, /fun selectedWhisperEngine\(\): OfflineSttEngine\?/);
  assert.match(manager, /modules\[WHISPER_LARGE_ID\]\?\.enabled == true -> OfflineSttEngine\.WHISPER_LARGE_V3_TURBO/);
  assert.match(manager, /rememberEnabled\(module\.spec\.id, true\)/);
  assert.match(manager, /rememberActive\(module\.spec\.id\)/);
  assert.match(manager, /getString\(KEY_ACTIVE, null\)/);
  assert.match(manager, /activeModuleId\?\.let \{ id ->/);
});

test('5. deactivation and deletion are separate operations', () => {
  const deactivate = body(manager, 'fun deactivate(id: String)');
  assert.match(deactivate, /runCatching \{ engine\.close\(\) \}/);
  assert.doesNotMatch(deactivate, /\.delete\(\)|deleteRecursively/);
  const deleteFn = body(manager, 'fun delete(id: String)');
  assert.match(deleteFn, /module\.spec\.files\.keys/);
  assert.match(deleteFn, /ModelIntegrity\.invalidate\(directory\)/);
  assert.match(deleteFn, /transition\(module, OfflineModuleState\.NOT_INSTALLED\)/);
  assert.match(deleteFn, /AvnivoOfflineModuleDeleted/);
  assert.doesNotMatch(deleteFn, /AvnivoOfflineModuleDeleted[^\n]*\n[\s\S]*failure != null/, 'success must not be emitted after a failure');
});

test('6. deletion is fail-closed and never reports a fake success', () => {
  const deleteFn = body(manager, 'fun delete(id: String)');
  assert.match(deleteFn, /if \(failure != null\)/);
  assert.match(deleteFn, /AvnivoOfflineModuleDeleteError/);
  assert.match(deleteFn, /transition\(module, OfflineModuleState\.ERROR\)/);
  assert.match(deleteFn, /remaining/);
});

test('7. routing uses the selected Whisper and leaves Uzbek/English on Uzbek STT', () => {
  assert.match(router, /WHISPER_LARGE_V3_TURBO\("whisper-large-v3-turbo"\)/);
  assert.match(router, /fun engineFor\(\s*sourceLanguage: String\?,\s*whisperEngine: OfflineSttEngine/);
  assert.match(router, /OfflineSttRouter\.route\(sourceLanguage, whisperEngine\(\)\)/);
  assert.match(router, /sourceLanguage == UZBEK \|\| sourceLanguage == ENGLISH/);
  assert.match(activity, /moduleManager\.selectedWhisperModuleId\(\)/);
  assert.match(activity, /moduleManager\.selectedWhisperEngine\(\)/);
  assert.match(activity, /route\.engine\.moduleId/);
});

test('8. the native engine exposes model metadata and fails on a non-zero whisper_full', () => {
  assert.match(jni, /WHISPER_INIT_OK/);
  assert.match(jni, /nativeModelInfo/);
  assert.match(jni, /whisper_model_n_vocab/);
  assert.match(jni, /whisper_full failed rc=/);
  assert.match(jni, /env->ThrowNew/);
});

test('9. the card has an explicit delete action and a confirmation', () => {
  assert.match(shipped, /offline-module-delete/);
  assert.match(shipped, /offlineSvg\('trash'\)/);
  assert.match(shipped, /window\.confirm\(module\.name/);
  assert.match(shipped, /AvnivoOfflineModuleDeleted=function/);
  assert.match(shipped, /AvnivoOfflineModuleDeleteError=function/);
  assert.match(shipped, /bridge\.delete\(module\.installerId\)/);
  assert.match(shipped, /AvnivoOfflineSTT\.delete\(\)/);
});

test('10. activation is separate from download in the card', () => {
  assert.match(shipped, /bridge\.activate\(module\.installerId\)/);
  assert.match(shipped, /bridge\.deactivate\(lifecycleId\)/);
  assert.match(shipped, /offlineState\.get\(module\.id\)==='installed'/);
  assert.match(shipped, /AvnivoOfflineSTT\.activate\(\)/);
  assert.match(shipped, /module\.serverModelId\?window\.AvnivoOfflineSTT:window\.AvnivoOfflineModules/);
  assert.match(shipped, /bridge\.deactivate\(\)/);
});

test('11. no automatic download is started for missing modules', () => {
  assert.doesNotMatch(activity, /onPageFinished[\s\S]{0,600}moduleManager\.download/);
  assert.match(manager, /if \(!module\.spec\.runnable\)/);
  assert.match(manager, /AvnivoOfflineModuleError/);
});

test('12. online paths are not touched by this stage', () => {
  // The only new bridge entry points are offline module lifecycle methods; no online endpoint,
  // voice or registry name appears in the added lifecycle block.
  const lifecycle = body(manager, 'fun activate(id: String)') + body(manager, 'fun deactivate(id: String)') + body(manager, 'fun delete(id: String)');
  assert.doesNotMatch(lifecycle, /chirp|gemini|online|apiBase/i);
});
test('13. a verified download may replace a stale destination', () => {
  const downloader = fs.readFileSync(path.join(main, 'VerifiedModelDownloader.kt'), 'utf8');
  assert.match(downloader, /if \(!temporary\.renameTo\(destination\)\)/);
  assert.match(downloader, /destination\.delete\(\)/);
  assert.match(downloader, /require\(temporary\.renameTo\(destination\)\)/);
});

test('14. an enabled module stays active while its engine is out of memory', () => {
  // Enabled is the user's intent; resident is where the bytes are. Evicting an engine must not
  // make the card claim the user switched the module off.
  assert.match(manager, /\.put\("enabled", module\.enabled\)/);
  assert.match(shipped, /const offlineActiveStates=new Set\(\['enabled'/);
  assert.match(shipped, /\(enabled===true&&state==='installed'\)\?'enabled'/);
  assert.match(shipped, /info\.enabled===true/);
  assert.match(shipped, /"offline\.state\.enabled"/);
  assert.match(manager, /private fun makeRoomFor\(module: Module\)/);
  assert.match(manager, /module\.enabled = false\n            rememberEnabled\(id, false\)/, 'deactivate must be the only switch-off path');
});
