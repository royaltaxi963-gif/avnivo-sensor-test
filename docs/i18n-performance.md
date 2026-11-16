# AVNIVO localization — performance and limitations

All numbers below are measured, not estimated. Re-run the commands to reproduce.

## Measured (Node v24.22.2, `node i18n/tests/benchmark.mjs`)

| Metric | Value |
| --- | --- |
| Registered languages | 100 |
| Total packaged dictionary bytes | 1,102,261 (≈1.05 MB) |
| Cold start (registry + first bundle + first apply) | 0.85 ms |
| First-visit language switch | avg 0.14 ms, p95 0.26 ms, max 0.77 ms |
| Warm switch | avg 0.12 ms, p95 0.22 ms, max 0.58 ms |
| Cached switch (alternating two languages) | avg 0.0014 ms, p95 0.0018 ms |
| Heap used after the run | 7.4 MB |
| RSS after the run | 59.9 MB |

## Measured in a real browser (Chromium, local server)

A probe page drove the actual engine:

* `dir=rtl` set for Arabic, `rtl=true`.
* Arabic `mode.online` rendered as `الوضع على الانترنت`.
* 200 alternating `ar`/`en` switches completed in **7.6 ms** total
  (≈0.038 ms per switch), with **2 bundles** resident (the cache limit is 3).
* Selected language persisted in `localStorage` under `avnivo.ui.lang.v1`.

## Design choices that keep switching cheap

* Only the selected bundle plus the fallback are in memory (LRU limit 3).
* Switching is `Promise`-based and never blocks the main thread.
* `apply()` updates only nodes carrying `data-i18n*` attributes; the DOM is not
  rebuilt, and no speech model or translation engine is reloaded.
* `cache: 'force-cache'` plus packaged files means no network round-trip once a
  bundle has been seen.

## Limitations (stated honestly)

* **No Android build or on-device run was performed.** The repository's
  `android/` tree is a scaffold: it has no `MainActivity` and no foreground
  transfer service (the manifest says so explicitly). Android Gradle plugin and
  SDK are not available in this environment, so `./gradlew assembleDebug` could
  not run. The native string resources are generated and wired into the manifest
  label, but compilation and device testing are unverified.
* The Node/browser numbers are engine-side. They exclude WebView layout and
  paint cost; real WebView switching will be slower than the figures above.
* All non-English dictionaries are machine-translated and unreviewed. See
  `i18n/translation-quality-report.md`.
* Low-memory conditions were not forced (no memory-pressure harness); the cache
  bound of 3 bundles and the 7.4 MB heap figure are the only memory evidence.
* `jsdom` is not installed; the test suite uses a minimal in-repo host shim
  (`i18n/tests/host.mjs`) instead of a full browser DOM. The real-browser probe
  above is the cross-check that the shim does not hide engine bugs.
