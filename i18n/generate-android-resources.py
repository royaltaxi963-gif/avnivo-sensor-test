#!/usr/bin/env python3
"""Generate Android values-<locale>/strings.xml for the native shell.

The native AVNIVO shell currently exposes only a few strings (the application
label today, foreground-service notification text once the transfer service is
implemented). This script maps the 100 interface languages from
``i18n/languages.json`` to Android resource qualifiers and writes a
``strings.xml`` per locale that has native strings to translate.

It reads the already-generated Web dictionaries in ``i18n/locales/`` and picks
the subset of keys declared in ``ANDROID_KEYS`` so the native and Web surfaces
stay consistent without a second translation pass.

Usage:
    python3 i18n/generate-android-resources.py
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
LOCALES = ROOT / "locales"
LANGUAGES = ROOT / "languages.json"
ANDROID_RES = REPO / "android" / "app" / "src" / "main" / "res"

# Source key -> Android resource name. Add entries here as the native shell grows.
ANDROID_KEYS = {
    "app.name": "app_name",
}

# The app's default interface language; its strings live in res/values and are
# maintained by hand (not regenerated) so the default folder always exists.
DEFAULT_CODE = "uz"

# Interface code -> Android values-<qualifier> folder suffix.
# Android uses BCP-47 style qualifiers; a bare two-letter code is accepted.
ANDROID_QUALIFIER = {
    "zh": "zh-rCN", "he": "iw", "id": "in", "yi": "yi",
}


def qualifier(code: str) -> str:
    return ANDROID_QUALIFIER.get(code, code)


def escape(value: str) -> str:
    return (value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("'", "\\'").replace('"', '\\"'))


def main() -> int:
    languages = json.loads(LANGUAGES.read_text(encoding="utf-8"))
    written = 0
    for lang in languages:
        code = lang["code"]
        if code == DEFAULT_CODE:
            continue  # res/values holds the default-language strings
        bundle_path = LOCALES / f"{code}.json"
        if not bundle_path.exists():
            continue
        bundle = json.loads(bundle_path.read_text(encoding="utf-8"))
        entries = []
        for key, res_name in ANDROID_KEYS.items():
            value = bundle.get(key)
            if value:
                entries.append(f'    <string name="{res_name}">{escape(value)}</string>')
        if not entries:
            continue
        folder = f"values-{qualifier(code)}"
        target = ANDROID_RES / folder
        target.mkdir(parents=True, exist_ok=True)
        (target / "strings.xml").write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
            + "\n".join(entries) + "\n</resources>\n",
            encoding="utf-8",
        )
        written += 1
    print(f"wrote {written} Android string resource folders")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
