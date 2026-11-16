#!/usr/bin/env python3
"""Generate the 100 AVNIVO interface dictionaries.

This is a development-time tool, not part of the runtime or CI. It translates
``i18n/source/en.json`` into every language in ``i18n/languages.json`` using the
public Google translate HTTP endpoint, protects interpolation placeholders and
HTML-like voice tags during translation, and writes one UTF-8 JSON package per
language under ``i18n/locales/``.

Every generated package is marked as machine-translated and NOT reviewed, so the
content is never presented as professionally checked. Languages that need human
linguistic review are listed in ``docs/i18n-quality-report.md``.

Usage:
    python3 i18n/generate.py            # regenerate every dictionary
    python3 i18n/generate.py uz ru de   # regenerate a subset
"""
from __future__ import annotations

import json
import re
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "source" / "en.json"
LANGUAGES = ROOT / "languages.json"
OUT = ROOT / "locales"

ENDPOINT = "https://clients5.google.com/translate_a/t"
BATCH = 10
PLACEHOLDER = re.compile(r"\{\{\s*[\w.-]+\s*\}\}")
VOICE_TAG = re.compile(r"</?[a-zA-Z][\w -]*>")


def protect(text: str) -> tuple[str, list[str]]:
    """Replace placeholders and voice tags with brace tokens.

    Brace tokens of the form ``{0}`` survive the public endpoint across every
    script (including CJK, Arabic, Cyrillic and Indic), unlike private-use
    markers which the engine strips. Each token maps back to the original text.
    """
    saved: list[str] = []

    def stash(match: re.Match[str]) -> str:
        saved.append(match.group(0))
        return f"{{{len(saved) - 1}}}"

    protected = PLACEHOLDER.sub(stash, text)
    protected = VOICE_TAG.sub(stash, protected)
    return protected, saved


def restore(text: str, saved: list[str]) -> str:
    for index, token in enumerate(saved):
        text = text.replace(f"{{{index}}}", token)
    return text


def request(strings: list[str], target: str) -> list[str]:
    params = [("client", "dict-chrome-ex"), ("sl", "en"), ("tl", target)]
    params += [("q", s) for s in strings]
    url = ENDPOINT + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=20) as response:
                data = json.load(response)
            if isinstance(data, list) and len(data) == len(strings):
                return [str(x) for x in data]
            if isinstance(data, str) and len(strings) == 1:
                return [data]
        except Exception:  # noqa: BLE001 - retry any transport error
            pass
        time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"translation failed for {target}: {strings[:2]}")


def integrity_ok(source: str, translated: str) -> bool:
    """The translated string must keep the same placeholders and voice tags."""
    return (sorted(PLACEHOLDER.findall(translated)) == sorted(PLACEHOLDER.findall(source))
            and sorted(VOICE_TAG.findall(translated)) == sorted(VOICE_TAG.findall(source)))


def translate_map(source: dict[str, str], target: str) -> dict[str, str]:
    keys = list(source)
    out: dict[str, str] = {}
    for start in range(0, len(keys), BATCH):
        chunk = keys[start:start + BATCH]
        protected: list[str] = []
        saved_per: list[list[str]] = []
        for key in chunk:
            value, saved = protect(source[key])
            protected.append(value)
            saved_per.append(saved)
        results = request(protected, target)
        for key, translated, saved in zip(chunk, results, saved_per):
            restored = restore(translated, saved)
            if integrity_ok(source[key], restored):
                out[key] = restored
            else:
                out[key] = repair(source[key], target)
        time.sleep(0.25)
    return out


def repair(source_value: str, target: str) -> str:
    """Re-translate a single string that lost placeholders or tags.

    Retries a few times; if the endpoint still mangles the tokens the English
    source is kept so the package stays structurally valid. Such a key is
    reported for human review rather than presented as a finished translation.
    """
    protected, saved = protect(source_value)
    for _ in range(3):
        try:
            translated = request([protected], target)[0]
        except RuntimeError:
            break
        restored = restore(translated, saved)
        if integrity_ok(source_value, restored):
            return restored
    return source_value


def build_package(code: str, source: dict[str, str], target: str, english: str) -> dict:
    if code == "en":
        messages = dict(source)
    else:
        messages = translate_map(source, target)
    package = {"_meta": {
        "code": code,
        "english": english,
        "machineTranslated": code != "en",
        "reviewed": False,
        "generator": "i18n/generate.py",
        "sourceLanguage": "en",
    }}
    package.update(messages)
    return package


def main(argv: list[str]) -> int:
    source = json.loads(SOURCE.read_text(encoding="utf-8"))
    languages = json.loads(LANGUAGES.read_text(encoding="utf-8"))
    wanted = set(argv[1:]) or {lang["code"] for lang in languages}
    OUT.mkdir(parents=True, exist_ok=True)
    selected = [lang for lang in languages if lang["code"] in wanted]

    def work(lang: dict) -> str:
        code = lang["code"]
        target = "en" if code == "en" else code
        package = build_package(code, source, target, lang["english"])
        (OUT / f"{code}.json").write_text(
            json.dumps(package, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        return f"wrote i18n/locales/{code}.json ({len(source)} keys)"

    # The public endpoint tolerates a few concurrent connections; keep it modest
    # so no single client is flooded and retries stay effective.
    with ThreadPoolExecutor(max_workers=4) as pool:
        for message in pool.map(work, selected):
            print(message)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
