/*
 * Element/Document/DocumentFragment.replaceChildren polyfill.
 *
 * The bundled AVNIVO UI calls replaceChildren(), which landed in Chrome 86.
 * WebView is updatable, but on minSdk 26 devices an outdated WebView can still
 * be present (the CI emulator image ships WebView 83), so the shell injects this
 * at document start. It is a no-op when the native method already exists.
 */
(function () {
  function install(proto) {
    if (!proto || typeof proto.replaceChildren === 'function') return;
    Object.defineProperty(proto, 'replaceChildren', {
      configurable: true,
      writable: true,
      value: function () {
        while (this.firstChild) this.removeChild(this.firstChild);
        var nodes = [];
        for (var i = 0; i < arguments.length; i++) {
          var n = arguments[i];
          nodes.push(n && n.nodeType ? n : document.createTextNode(String(n)));
        }
        for (var j = 0; j < nodes.length; j++) this.appendChild(nodes[j]);
      }
    });
  }
  install(typeof Element !== 'undefined' && Element.prototype);
  install(typeof Document !== 'undefined' && Document.prototype);
  install(typeof DocumentFragment !== 'undefined' && DocumentFragment.prototype);
})();
