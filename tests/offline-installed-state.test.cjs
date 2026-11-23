/*
 * Node tests: a downloaded model is not a broken engine.
 *
 * On the S23 Ultra every installed module read "Yuklab olingan va tasdiqlangan · dvigatel
 * ulanmagan", and the module states appeared to be lost after a restart. Both came from the same
 * place: the native side mapped "installed" onto the old "downloaded, engine not connected"
 * wording, which was written when no engine existed. Engines exist now, and an installed model
 * loads on first use, so the card has to say that instead.
 */
const { test } = require('node:test');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const path = require('node:path');

const root = path.join(__dirname, '..');
const shipped = fs.readFileSync(
  path.join(root, 'android-app', 'app', 'src', 'main', 'assets', 'index.html'),
  'utf8'
);

const renderer = shipped.slice(shipped.indexOf('function updateOfflineModule('));

test('1. the renderer accepts the installed state', () => {
  const allowed = shipped.match(/const offlineStateKeys=\{([^}]*)\}/);
  assert.ok(allowed, 'the renderer must whitelist the states it can draw');
  assert.ok(
    allowed[1].includes("installed:'offline.state.installed'"),
    `installed must be drawable, found: ${allowed[1]}`
  );
});

test('2. native installed no longer means the engine is disconnected', () => {
  const map = shipped.match(/const offlineStateByInstaller=\{([^}]*)\}/);
  assert.ok(map, 'the installer->card state map must exist');
  assert.ok(
    !/installed:'downloaded'/.test(map[1]),
    `installed must not collapse into the old "engine not connected" state: ${map[1]}`
  );
  assert.ok(
    /installed:'installed'/.test(map[1]),
    `installed must map to itself: ${map[1]}`
  );
});

test('3. the installed wording says it loads on use, not that the engine is missing', () => {
  const fallbacks = [...shipped.matchAll(/installed:'([^']*)'/g)].map((match) => match[1]);
  assert.ok(fallbacks.length > 0, 'the installed state must have user-facing wording');
  for (const text of fallbacks) {
    assert.ok(
      !/dvigatel ulanmagan/i.test(text),
      `an installed model must not claim the engine is missing: ${text}`
    );
  }
  assert.ok(
    fallbacks.some((text) => /ishlatilganda yuklanadi/i.test(text)),
    `the wording must explain on-demand loading: ${fallbacks.join(' | ')}`
  );
});

test('4. every state the renderer draws has wording in all three bundles', () => {
  for (const state of ['not_installed', 'downloading', 'verifying', 'installed', 'ready', 'error']) {
    const key = `offline.state.${state}`;
    const occurrences = shipped.split(`"${key}"`).length - 1;
    assert.ok(occurrences >= 3, `${key} must be translated in uz, ru and en, found ${occurrences}`);
  }
});

test('5. a native failure reason still reaches its own card', () => {
  assert.ok(
    /info\.reason/.test(shipped) && /offline-module-reason/.test(shipped),
    'the card must render the native reason instead of hiding it'
  );
});
