#!/usr/bin/env python3
"""Translate the onboarding tutorial keys into the remaining languages.

The five-step onboarding tutorial adds eleven ``tutorial.*`` keys to
``i18n/source/en.json``. This focused generator fills exactly those keys into
every language package, leaving every other key untouched, so a run cannot
disturb already-completed interface translations.

Behaviour:
- ``uz`` is skipped: its tutorial text is hand-written and must be preserved.
- ``en`` receives the canonical English strings from the source file.
- Every other language is machine-translated and keeps its existing ``_meta``
  (``machineTranslated``/``reviewed`` are never upgraded to reviewed).

Usage:
    python3 i18n/generate-tutorial.py              # all languages
    python3 i18n/generate-tutorial.py de fr ar     # a subset
"""
from __future__ import annotations

import html
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
MYMEMORY = "https://api.mymemory.translated.net/get"
PREFIX = "tutorial."
PRESERVE = {"uz"}  # hand-written tutorial text that must not be overwritten
PLACEHOLDER = re.compile(r"\{\{\s*[\w.-]+\s*\}\}")
VOICE_TAG = re.compile(r"</?[a-zA-Z][\w -]*>")


def protect(text: str) -> tuple[str, list[str]]:
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
    for attempt in range(5):
        try:
            with urllib.request.urlopen(req, timeout=25) as response:
                data = json.load(response)
            if isinstance(data, list) and len(data) == len(strings):
                return [str(x) for x in data]
            if isinstance(data, str) and len(strings) == 1:
                return [data]
        except Exception:  # noqa: BLE001 - retry any transport error
            pass
        time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"translation failed for {target}")


def integrity_ok(source: str, translated: str) -> bool:
    return (sorted(PLACEHOLDER.findall(translated)) == sorted(PLACEHOLDER.findall(source))
            and sorted(VOICE_TAG.findall(translated)) == sorted(VOICE_TAG.findall(source)))


def translate_one(value: str, target: str) -> str:
    protected, saved = protect(value)
    for _ in range(4):
        translated = restore(request([protected], target)[0], saved)
        if integrity_ok(value, translated) and translated.strip():
            return translated
    return value


def still_english(text: str, source: str) -> bool:
    """True when a title is unchanged English or still carries the English label.

    The endpoint sometimes returns the source verbatim ("Settings") and
    sometimes wraps it in local words ("iyo online mode"). Both keep the English
    label, so both count as untranslated rather than finished.
    """
    norm = lambda s: re.sub(r"[^\w\s]", "", s).strip().casefold()
    text_n, source_n = norm(text), norm(source)
    return text_n == source_n or (len(source_n) > 3 and source_n in text_n)


def translate_title(value: str, target: str) -> tuple[str, bool]:
    """Translate a short title, working around the endpoint's habit of echoing
    short English UI labels back untranslated for lower-resource languages.

    Tries, in order: the label as written, lowercase, and prefixed with a
    leading article ("the ..."), then a second public engine (MyMemory) whose
    phrase memory covers some titles the first engine leaves in English.
    Returns (text, translated?) so the caller can record a gap instead of
    passing English off as a finished translation.
    """
    for candidate in (value, value.lower(), "the " + value.lower()):
        result = translate_one(candidate, target)
        if result.strip() and not still_english(result, value):
            return capitalize(result), True
    alt = mymemory(value, target)
    if alt and not still_english(alt, value):
        return capitalize(alt), True
    return value, False


def capitalize(text: str) -> str:
    """Upper-case the first letter of a short title, leaving the rest intact."""
    text = text.strip()
    return text[:1].upper() + text[1:] if text else text


def mymemory(value: str, target: str) -> str | None:
    """Second-opinion title translation; returns None when unavailable."""
    params = urllib.parse.urlencode({"q": value, "langpair": f"en|{target}"})
    req = urllib.request.Request(f"{MYMEMORY}?{params}", headers={"User-Agent": "Mozilla/5.0"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=20) as response:
                data = json.load(response)
            text = data.get("responseData", {}).get("translatedText")
            if text:
                return html.unescape(text).strip()
        except Exception:  # noqa: BLE001 - optional fallback
            pass
        time.sleep(1.0 * (attempt + 1))
    return None


def translate_block(keys: list[str], source: dict[str, str], target: str) -> tuple[dict[str, str], list[str]]:
    """Translate the whole block in one request, repairing any mangled string.

    Returns the translated block and the list of keys that could not be
    translated (the endpoint returned English) so they can be declared as gaps.
    """
    protected: list[str] = []
    saved_per: list[list[str]] = []
    for key in keys:
        value, saved = protect(source[key])
        protected.append(value)
        saved_per.append(saved)
    results = request(protected, target)
    out: dict[str, str] = {}
    for key, translated, saved in zip(keys, results, saved_per):
        restored = restore(translated, saved)
        if integrity_ok(source[key], restored) and restored.strip():
            out[key] = restored
        else:
            out[key] = translate_one(source[key], target)
    gaps: list[str] = []
    # Titles are short labels the endpoint often echoes; retry those.
    for key in keys:
        if key.endswith('.title') and still_english(out[key], source[key]):
            text, ok = translate_title(source[key], target)
            out[key] = text
            if not ok:
                gaps.append(key)
    return out, gaps


def main(argv: list[str]) -> int:
    source = json.loads(SOURCE.read_text(encoding="utf-8"))
    languages = json.loads(LANGUAGES.read_text(encoding="utf-8"))
    keys = [k for k in source if k.startswith(PREFIX)]
    if not keys:
        raise SystemExit("no tutorial.* keys found in source")

    wanted = set(argv[1:]) or {lang["code"] for lang in languages}
    selected = [lang for lang in languages if lang["code"] in wanted]
    gaps: dict[str, list[str]] = {}

    def work(lang: dict) -> str:
        code = lang["code"]
        path = OUT / f"{code}.json"
        package = json.loads(path.read_text(encoding="utf-8"))

        if code in PRESERVE:
            return f"kept {code} (hand-written tutorial preserved)"

        if code == "en":
            block = {k: source[k] for k in keys}
            block_gaps: list[str] = []
        else:
            block, block_gaps = translate_block(keys, source, code)
            if block_gaps:
                gaps[code] = block_gaps
            time.sleep(0.2)

        # Merge only the tutorial keys; leave every other key and _meta intact.
        for key, value in block.items():
            package[key] = value
        path.write_text(json.dumps(package, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        note = f" ({len(block_gaps)} untranslated titles)" if block_gaps else ""
        return f"wrote {code} ({len(block)} tutorial keys){note}"

    with ThreadPoolExecutor(max_workers=4) as pool:
        for message in pool.map(work, selected):
            print(message)

    report = ROOT / "tutorial-generation-report.json"
    report.write_text(json.dumps({
        "languages": len(selected),
        "keysPerLanguage": len(keys),
        "untranslatedTitles": gaps,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if gaps:
        print(f"untranslated titles left as English (need human review): "
              f"{sum(len(v) for v in gaps.values())} across {len(gaps)} languages")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
