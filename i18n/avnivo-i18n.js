/* AVNIVO interface localization engine.
 *
 * A dependency-free, offline-first i18n engine. It provides the feature surface
 * AVNIVO needs (per-language JSON packages, lazy loading with an LRU cache,
 * interpolation, plural forms, fallback, RTL direction, locale-aware number and
 * date formatting, persistence and targeted DOM updates) without a CDN build or
 * a bundler, so the single-file app keeps working inside an Android WebView
 * served from local assets and on raw.githack.com with no network dependency.
 *
 * See i18n/README.md for the rationale against i18next.
 */
(function (global) {
  'use strict';

  var STORAGE_KEY = 'avnivo.ui.lang.v1';
  var DEFAULT_LANG = 'uz';
  var FALLBACK_LANG = 'en';
  var CACHE_LIMIT = 3;
  // Right-to-left scripts. Kurdish ("ku") is intentionally absent: its package
  // is Latin-script Kurmanji (native name "Kurdî"), so its UI must stay LTR.
  var RTL_LANGS = { ar: 1, fa: 1, ur: 1, ps: 1, ug: 1, sd: 1, dv: 1, he: 1 };

  var state = {
    language: DEFAULT_LANG,
    registry: null,
    byCode: null,
    loaded: new Map(),
    order: [],
    listeners: [],
    ready: false
  };

  function dirFor(code) {
    return RTL_LANGS[String(code).split('-')[0]] ? 'rtl' : 'ltr';
  }

  function readSaved() {
    try {
      var saved = global.localStorage.getItem(STORAGE_KEY);
      return saved || null;
    } catch (e) {
      return null;
    }
  }

  function persist(code) {
    try {
      global.localStorage.setItem(STORAGE_KEY, code);
    } catch (e) {
      /* private mode / storage disabled: switching still works in memory */
    }
  }

  function resourceUrl(code) {
    return 'i18n/locales/' + code + '.json';
  }

  function loadBundle(code) {
    if (state.loaded.has(code)) {
      // Refresh LRU position.
      var idx = state.order.indexOf(code);
      if (idx !== -1) state.order.splice(idx, 1);
      state.order.push(code);
      return Promise.resolve(state.loaded.get(code));
    }
    var inline = global.AVNIVO_I18N_BUNDLE && global.AVNIVO_I18N_BUNDLE[code];
    var pending = inline
      ? Promise.resolve(inline)
      : fetch(resourceUrl(code), { cache: 'force-cache' }).then(function (response) {
          if (!response.ok) throw new Error('i18n bundle ' + code + ' HTTP ' + response.status);
          return response.json();
        });
    return pending.then(function (bundle) {
      state.loaded.set(code, bundle);
      state.order.push(code);
      while (state.order.length > CACHE_LIMIT) {
        var evicted = state.order.shift();
        if (evicted !== state.language) state.loaded.delete(evicted);
      }
      return bundle;
    });
  }

  function loadRegistry() {
    if (state.registry) return Promise.resolve(state.registry);
    if (global.AVNIVO_I18N_LANGUAGES) {
      state.registry = global.AVNIVO_I18N_LANGUAGES;
      return Promise.resolve(state.registry);
    }
    return fetch('i18n/languages.json', { cache: 'force-cache' })
      .then(function (r) { return r.json(); })
      .then(function (list) {
        state.registry = list;
        return list;
      });
  }

  function currentBundle() {
    return state.loaded.get(state.language) || null;
  }

  function lookup(bundle, key) {
    if (!bundle) return undefined;
    if (Object.prototype.hasOwnProperty.call(bundle, key)) return bundle[key];
    // Nested namespaces are stored flat, but tolerate dotted objects too.
    var parts = key.split('.');
    var node = bundle;
    for (var i = 0; i < parts.length; i++) {
      if (node && typeof node === 'object' && parts[i] in node) node = node[parts[i]];
      else return undefined;
    }
    return node;
  }

  function interpolate(template, vars) {
    if (typeof template !== 'string' || !vars) return template;
    return template.replace(/\{\{\s*([\w.-]+)\s*\}\}/g, function (match, name) {
      var value = vars[name];
      return value === undefined || value === null ? match : String(value);
    });
  }

  function pluralKey(bundle, key, count) {
    var category = count === 1 ? 'one' : 'other';
    var candidate = key + '_' + category;
    if (lookup(bundle, candidate) !== undefined) return candidate;
    if (lookup(bundle, key + '_other') !== undefined) return key + '_other';
    return key;
  }

  function resolve(key, vars) {
    var bundle = currentBundle();
    var effectiveKey = key;
    if (vars && typeof vars.count === 'number') effectiveKey = pluralKey(bundle, key, vars.count);
    var value = lookup(bundle, effectiveKey);
    if (value === undefined && state.language !== FALLBACK_LANG) {
      var fallback = state.loaded.get(FALLBACK_LANG);
      value = lookup(fallback, effectiveKey);
    }
    if (value === undefined) {
      // Never surface a raw key to the user: fall back to a human-readable tail.
      return humanize(key);
    }
    return interpolate(value, vars);
  }

  function humanize(key) {
    var tail = String(key).split('.').pop();
    tail = tail.replace(/_/g, ' ').replace(/([a-z])([A-Z])/g, '$1 $2');
    return tail.charAt(0).toUpperCase() + tail.slice(1);
  }

  function t(key, vars) {
    return resolve(key, vars);
  }

  function formatNumber(value, options) {
    try {
      return new Intl.NumberFormat(state.language, options).format(value);
    } catch (e) {
      return String(value);
    }
  }

  function formatDate(value, options) {
    try {
      return new Intl.DateTimeFormat(state.language, options).format(value);
    } catch (e) {
      return String(value);
    }
  }

  function applyDirection() {
    var root = global.document && global.document.documentElement;
    if (!root) return;
    var dir = dirFor(state.language);
    root.setAttribute('dir', dir);
    root.setAttribute('lang', state.language);
  }

  function applyBinding(node) {
    var key = node.getAttribute('data-i18n');
    if (key) {
      var vars = node.getAttribute('data-i18n-vars');
      node.textContent = t(key, vars ? JSON.parse(vars) : undefined);
      // Let each translated text node choose its own direction from its content
      // so RTL script renders right-to-left without mirroring the AVNIVO layout.
      if (!node.hasAttribute('data-i18n-dir') && !node.getAttribute('dir')) {
        node.setAttribute('dir', 'auto');
      }
    }
    var attrs = ['aria-label', 'title', 'placeholder'];
    for (var i = 0; i < attrs.length; i++) {
      var attrKey = node.getAttribute('data-i18n-' + attrs[i]);
      if (attrKey) node.setAttribute(attrs[i], t(attrKey));
    }
  }

  // Targeted update: only re-translate nodes that opt in, never rebuild the DOM.
  function apply(root) {
    var scope = root || global.document;
    if (!scope || !scope.querySelectorAll) return;
    var nodes = scope.querySelectorAll('[data-i18n],[data-i18n-aria-label],[data-i18n-title],[data-i18n-placeholder]');
    for (var i = 0; i < nodes.length; i++) applyBinding(nodes[i]);
  }

  function notify() {
    for (var i = 0; i < state.listeners.length; i++) {
      try { state.listeners[i](state.language); } catch (e) { /* isolate listeners */ }
    }
  }

  function setLanguage(code) {
    return loadRegistry().then(function () {
      var known = state.byCode.has(code);
      var next = known ? code : FALLBACK_LANG;
      return loadBundle(next).then(function () {
        if (next !== FALLBACK_LANG && !state.loaded.has(FALLBACK_LANG)) {
          return loadBundle(FALLBACK_LANG).then(function () { return next; });
        }
        return next;
      }).then(function (resolved) {
        state.language = resolved;
        persist(resolved);
        applyDirection();
        apply();
        notify();
        return resolved;
      });
    });
  }

  function init(options) {
    options = options || {};
    if (options.defaultLanguage) DEFAULT_LANG = options.defaultLanguage;
    return loadRegistry().then(function (list) {
      state.byCode = new Map(list.map(function (item) { return [item.code, item]; }));
      var saved = readSaved();
      var requested = options.language || saved || DEFAULT_LANG;
      var initial;
      if (state.byCode.has(requested)) {
        initial = requested;
      } else if (options.language) {
        // An explicitly requested but unknown language resolves to the fallback.
        initial = FALLBACK_LANG;
      } else {
        // A stale saved value or a missing default resolves to the app default.
        initial = state.byCode.has(DEFAULT_LANG) ? DEFAULT_LANG : FALLBACK_LANG;
      }
      state.ready = true;
      return setLanguage(initial);
    });
  }

  function isRTL(code) {
    return dirFor(code || state.language) === 'rtl';
  }

  function languages() {
    return state.registry ? state.registry.slice() : [];
  }

  global.AVNIVO_I18N = {
    init: init,
    t: t,
    setLanguage: setLanguage,
    getLanguage: function () { return state.language; },
    isRTL: isRTL,
    languages: languages,
    apply: apply,
    formatNumber: formatNumber,
    formatDate: formatDate,
    dirFor: dirFor,
    onChange: function (cb) { state.listeners.push(cb); },
    FALLBACK_LANG: FALLBACK_LANG,
    STORAGE_KEY: STORAGE_KEY,
    _loadedCount: function () { return state.loaded.size; }
  };
})(typeof window !== 'undefined' ? window : this);
