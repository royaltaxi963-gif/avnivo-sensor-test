/*
 * Node test: the shipped WebView asset must be a strict superset of the original interface.
 *
 * Offline work is only allowed to add to the AVNIVO UI. This guards that rule mechanically
 * against the protected baseline, so a future edit cannot quietly restyle or drop a control
 * while every Android test still passes. The baseline file is committed verbatim, because the
 * protected commit is not an ancestor of this branch.
 */
const { test } = require('node:test');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const path = require('node:path');

const root = path.join(__dirname, '..');
const baselinePath = path.join(__dirname, 'fixtures', 'avnivo-baseline-index.html');
const shippedPath = path.join(root, 'android-app', 'app', 'src', 'main', 'assets', 'index.html');

const baseline = fs.readFileSync(baselinePath, 'utf8');
const shipped = fs.readFileSync(shippedPath, 'utf8');

/** Collect CSS declarations per selector from every <style> block. */
function declarations(html) {
  const css = [...html.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)].map((m) => m[1]).join('\n');
  const out = new Map();
  for (const match of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    const selector = match[1].trim().split('\n').pop().trim().replace(/\s+/g, ' ');
    const body = match[2].replace(/\s+/g, ' ').trim();
    if (!selector) continue;
    if (!out.has(selector)) out.set(selector, []);
    out.get(selector).push(body);
  }
  return out;
}

function ids(html) {
  return new Set([...html.matchAll(/\bid="([^"]+)"/g)].map((m) => m[1]));
}

function classes(html) {
  const out = new Set();
  for (const match of html.matchAll(/\bclass="([^"]+)"/g)) {
    for (const name of match[1].split(/\s+/)) if (name) out.add(name);
  }
  return out;
}

const baselineRules = declarations(baseline);
const shippedRules = declarations(shipped);

test('every original CSS selector still exists', () => {
  const missing = [...baselineRules.keys()].filter((selector) => !shippedRules.has(selector));
  assert.deepEqual(missing, [], `selectors dropped from the interface: ${missing.join(', ')}`);
});

test('every original CSS declaration is unchanged', () => {
  const altered = [];
  for (const [selector, bodies] of baselineRules) {
    const now = shippedRules.get(selector) ?? [];
    // Later declarations may be appended for new breakpoints, but the original block must
    // remain byte-identical and in the same position.
    if (now.slice(0, bodies.length).join('|') !== bodies.join('|')) altered.push(selector);
  }
  assert.deepEqual(altered, [], `selectors restyled: ${altered.join(', ')}`);
});

test('no original element id is removed', () => {
  const missing = [...ids(baseline)].filter((id) => !ids(shipped).has(id));
  assert.deepEqual(missing, [], `element ids removed: ${missing.join(', ')}`);
});

test('no original class name is removed', () => {
  const now = classes(shipped);
  const missing = [...classes(baseline)].filter((name) => !now.has(name));
  assert.deepEqual(missing, [], `class names removed: ${missing.join(', ')}`);
});

test('all seven offline modules stay wired into their settings sections', () => {
  const modules = [
    'uz-stt',
    'multilingual-stt',
    'multilingual-stt-large',
    'offline-translate',
    'offline-translate-large',
    'offline-tts-navoiy',
    'offline-tts'
  ];
  const missing = modules.filter((id) => !shipped.includes(`id:'${id}'`));
  assert.deepEqual(missing, [], `offline modules missing from the catalog: ${missing.join(', ')}`);
});
