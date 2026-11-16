/*
 * Executes the WebView replaceChildren polyfill (the exact asset bundled into the
 * APK) against a minimal DOM stub, so the shim is verified by running it rather
 * than by inspecting it. Run with: node android/app/src/test/js/polyfill.test.mjs
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import assert from 'node:assert/strict';

const here = dirname(fileURLToPath(import.meta.url));
const source = readFileSync(
  join(here, '..', '..', 'main', 'assets', 'polyfill-replace-children.js'),
  'utf8'
);

let passed = 0;
let failed = 0;
function test(name, fn) {
  try {
    fn();
    passed++;
    console.log(`PASS  ${name}`);
  } catch (err) {
    failed++;
    console.log(`FAIL  ${name}: ${err.message}`);
  }
}

class Node {
  constructor(nodeType) {
    this.nodeType = nodeType;
    this.childNodes = [];
  }
  get firstChild() {
    return this.childNodes.length ? this.childNodes[0] : null;
  }
  appendChild(n) {
    this.childNodes.push(n);
    return n;
  }
  removeChild(n) {
    const i = this.childNodes.indexOf(n);
    if (i >= 0) this.childNodes.splice(i, 1);
    return n;
  }
}

function makeElement(s) {
  const el = Object.create(s.Element.prototype);
  el.nodeType = 1;
  el.childNodes = [];
  return el;
}

function runPolyfill() {
  const sandbox = {
    Element: { prototype: new Node(1) },
    Document: { prototype: new Node(9) },
    DocumentFragment: { prototype: new Node(11) },
    document: { createTextNode: (s) => ({ nodeType: 3, text: s }) }
  };
  const fn = new Function(
    'Element', 'Document', 'DocumentFragment', 'document',
    source
  );
  fn(
    sandbox.Element, sandbox.Document, sandbox.DocumentFragment, sandbox.document
  );
  return sandbox;
}

test('installs replaceChildren on Element.prototype', () => {
  const s = runPolyfill();
  assert.equal(typeof s.Element.prototype.replaceChildren, 'function');
});

test('replaces existing children with new nodes', () => {
  const s = runPolyfill();
  const parent = makeElement(s);
  parent.appendChild({ nodeType: 1, id: 'old' });
  const a = { nodeType: 1, id: 'a' };
  const b = { nodeType: 1, id: 'b' };
  parent.replaceChildren(a, b);
  assert.equal(parent.childNodes.length, 2);
  assert.equal(parent.childNodes[0].id, 'a');
  assert.equal(parent.childNodes[1].id, 'b');
});

test('clears children when called with no arguments', () => {
  const s = runPolyfill();
  const parent = makeElement(s);
  parent.appendChild({ nodeType: 1 });
  parent.replaceChildren();
  assert.equal(parent.childNodes.length, 0);
});

test('coerces strings to text nodes', () => {
  const s = runPolyfill();
  const parent = makeElement(s);
  parent.replaceChildren('hello');
  assert.equal(parent.childNodes.length, 1);
  assert.equal(parent.childNodes[0].nodeType, 3);
  assert.equal(parent.childNodes[0].text, 'hello');
});

test('does not overwrite an existing native implementation', () => {
  const sentinel = function native() { return 'native'; };
  const sandbox = {
    Element: { prototype: { replaceChildren: sentinel } },
    Document: { prototype: {} },
    DocumentFragment: { prototype: {} },
    document: { createTextNode: (s) => ({ nodeType: 3, text: s }) }
  };
  const fn = new Function(
    'Element', 'Document', 'DocumentFragment', 'document', source
  );
  fn(sandbox.Element, sandbox.Document, sandbox.DocumentFragment, sandbox.document);
  assert.equal(sandbox.Element.prototype.replaceChildren, sentinel);
});

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
