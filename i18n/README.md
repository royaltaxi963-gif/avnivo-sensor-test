# AVNIVO localization (100 interface languages)

Offline-capable interface localization for the AVNIVO Translator web app
(`index.html`) and the onboarding screen (`onboarding-preview.html`), with
Android-native string resources for the parts of the shell that render text.

## Why not i18next

The brief allows "i18next or an equivalently lightweight, justified solution".
AVNIVO is a single self-contained HTML file that must run:

* inside an Android WebView served from local assets, and
* on `raw.githack.com`, with no build step.

A CDN copy of i18next would add a network dependency and break the offline
requirement; vendoring it would add ~40 KB plus a plugin for HTTP loading and a
second one for plurals. The engine here is ~6 KB, has no dependencies, loads
exactly what it needs and keeps the app a single file. It implements the same
feature surface the brief asks for.

## Layout

```
i18n/
  avnivo-i18n.js               engine (window.AVNIVO_I18N)
  languages.json               100-language registry (code, native name, RTL)
  source/en.json               canonical English source strings (168 keys)
  locales/<code>.json          one UTF-8 package per language
  validate.mjs                 structural + quality validation
  quality-report.mjs           machine-translation / human-review report
  generate.py                  regeneration (machine translation, dev only)
  generate-android-resources.py Android values-<locale>/strings.xml
  tests/host.mjs               minimal DOM + fetch shim (no npm dependencies)
  tests/run-tests.mjs          acceptance test suite
  tests/benchmark.mjs          real latency and memory measurement
```

## Usage

```html
<script src="i18n/avnivo-i18n.js"></script>
<script>
  AVNIVO_I18N.init().then(function () {
    AVNIVO_I18N.apply();          // translate every [data-i18n] node
    AVNIVO_I18N.onChange(refresh); // re-run targeted updates on switch
  });
</script>
```

Mark static strings with attributes; the engine updates only those nodes:

```html
<button data-i18n="mode.online"
        data-i18n-aria-label="mode.online"></button>
<input data-i18n-placeholder="composer.placeholder"
       data-i18n-title="composer.hint">
<span data-i18n="history.saved" data-i18n-vars='{"date":"12.05.2026"}'></span>
```

For nodes created at runtime, call `AVNIVO_I18N.t(key, vars)` and re-run your
own render on `onChange`.

## Commands

```bash
node i18n/validate.mjs             # validate all 100 dictionaries
node i18n/tests/run-tests.mjs      # run the acceptance suite
node i18n/tests/benchmark.mjs      # measure switching latency and memory
node i18n/quality-report.mjs       # rebuild the human-review report
python3 i18n/generate.py           # regenerate dictionaries (network required)
python3 i18n/generate-android-resources.py
```

## Machine translation

Every non-English package is machine-translated and marked
`"_meta": { "machineTranslated": true, "reviewed": false }`. It must not be
presented as professionally reviewed. See
`i18n/translation-quality-report.md` for the languages that need a human
linguist, ranked by risk.

## Offline behaviour

Packages are fetched with `cache: 'force-cache'` and are packaged as static
files. In the Android WebView they are served from `file:///android_asset/`, so
switching language never touches the network. No speech model or translation
engine is reloaded on a language switch — only the interface strings change.
