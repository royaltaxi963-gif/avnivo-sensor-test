/* Minimal host environment for exercising the real AVNIVO i18n engine in Node.
 *
 * This provides browser primitives (localStorage, fetch over local files, a tiny
 * DOM) so the tests run the genuine engine code rather than a mock of it. Only
 * the surrounding host is simulated; the unit under test is untouched.
 */
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

class Storage {
  constructor() { this.map = new Map(); }
  getItem(k) { return this.map.has(k) ? this.map.get(k) : null; }
  setItem(k, v) { this.map.set(k, String(v)); }
  removeItem(k) { this.map.delete(k); }
  clear() { this.map.clear(); }
}

class Element {
  constructor(tag) {
    this.tagName = tag;
    this.attributes = {};
    this.children = [];
    this.textContent = '';
  }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  getAttribute(name) { return name in this.attributes ? this.attributes[name] : null; }
  hasAttribute(name) { return name in this.attributes; }
}

class Document {
  constructor() {
    this.documentElement = new Element('html');
    this._nodes = [];
  }
  register(node) { this._nodes.push(node); return node; }
  querySelectorAll() { return this._nodes; }
}

function createFetch() {
  const calls = [];
  const fetchImpl = async (url) => {
    calls.push(url);
    const clean = String(url).replace(/^\.?\//, '');
    const path = join(repoRoot, clean);
    if (!existsSync(path)) return { ok: false, status: 404, json: async () => ({}) };
    const text = readFileSync(path, 'utf8');
    return { ok: true, status: 200, json: async () => JSON.parse(text) };
  };
  fetchImpl.calls = calls;
  return fetchImpl;
}

export function createHost() {
  const window = {
    localStorage: new Storage(),
    document: new Document(),
    fetch: createFetch(),
    Intl,
  };
  window.window = window;
  return window;
}

export function loadEngine(window) {
  const code = readFileSync(join(repoRoot, 'i18n', 'avnivo-i18n.js'), 'utf8');
  const fn = new Function('window', 'fetch', 'Intl', 'document', 'localStorage', code + '\n;return window.AVNIVO_I18N;');
  return fn(window, window.fetch, Intl, window.document, window.localStorage);
}

export { repoRoot };
