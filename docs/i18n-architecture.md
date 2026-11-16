# AVNIVO localization — architecture

This document describes the 100-language interface localization added on top of
the existing AVNIVO application. Nothing existing was redesigned: the Online and
Offline modes stay separate, the animated AVNIVO brand and its multilingual
typewriter effect are untouched, and the onboarding language-selection animation
is preserved as its own component.

## Components

| File | Purpose |
| --- | --- |
| `i18n/avnivo-i18n.js` | Dependency-free engine: lazy bundles, LRU cache, interpolation, plurals, fallback, RTL, locale formatting, persistence, targeted DOM updates |
| `i18n/languages.json` | Registry of exactly 100 interface languages with native names and RTL flags |
| `i18n/locales/<code>.json` | One UTF-8 JSON package per language |
| `i18n/source/en.json` | Canonical English source strings |
| `i18n/validate.mjs` | Structural + quality validation of every dictionary |
| `i18n/tests/run-tests.mjs` | Automated acceptance tests (10 areas) |
| `i18n/tests/benchmark.mjs` | Real switching-latency and memory measurement |
| `i18n/generate.py` | Regeneration script (machine translation, development only) |
| `i18n/generate-android-resources.py` | Emits Android `values-<locale>/strings.xml` |
| `.github/workflows/avnivo-i18n.yml` | CI: validate + test on every change |

## Engine design

The engine is intentionally small (about 6 KB) and offline. It is the
"equivalently lightweight, justified solution" allowed by the brief instead of
i18next, because the app is a single self-contained HTML file that must run
offline inside an Android WebView served from local assets and on
`raw.githack.com` with no bundler. A CDN copy of i18next would break the offline
requirement.

### Loading model

* Only the selected language is fetched into active memory.
* A small LRU cache (3 bundles) keeps the current language plus its immediate
  neighbours so repeated switching is instant; the rest are released.
* `fetch(..., { cache: 'force-cache' })` plus packaged files means no network is
  required after first load, and the Android asset loader serves them locally.
* Switching is asynchronous (`Promise`-based) and never blocks the UI thread.

### Translation contract

* Keys are flat, dotted namespaces (`offline.state.downloading`).
* Interpolation uses `{{name}}`; missing variables are left visible rather than
  silently blanked.
* Plural forms resolve `key_one` / `key_other` when a `count` variable is given.
* A missing key falls back to English, and if English is also missing the key is
  humanised (`offline.state.downloading` → "Downloading") so a raw key is never
  shown to a user.
* RTL languages set `dir="rtl"` on the document root; a mirror `dir="ltr"` is
  restored for LTR languages.

### UI integration

* Static strings use `data-i18n`, `data-i18n-aria-label`, `data-i18n-title`,
  `data-i18n-placeholder` and `data-i18n-vars` attributes. `apply()` updates only
  those nodes; the DOM is never rebuilt.
* Dynamically created strings (offline module cards, TTS option labels, history
  entries, status messages) register through `bindLocalized()` so they re-render
  on language change without re-creating their elements.
* The interface-language selector lives in the settings drawer and is populated
  from the registry. It does not touch speech models or translation engines.

### Persistence

The selected language is stored in `localStorage` under `avnivo.ui.lang.v1` and
restored on the next session. If storage is unavailable (private mode) the
switch still works in memory for the session.

## What is intentionally not translated

Per the brief: user-generated chat content, API payloads, language codes, file
paths and technical identifiers. The Online locale picker still shows locale
codes (`uz-UZ`) because those are API identifiers, and the AVNIVO brand letters
stay as-is.
