"""Shared, evidence-based quality scoring for the Translation LLM audit.

Deliberately conservative: a flag marks something worth a human look, never a
silent pass or fail. Numbers are compared after Unicode digit normalisation so
locales that render digits in their own script (Bengali, Devanagari, Arabic,
Khmer, Myanmar, ...) are not falsely flagged. An output identical to the source
is only flagged when the source and target are genuinely different languages.
"""
from __future__ import annotations

import unicodedata

from corpus import NUMERIC_EXPECTATIONS

# Acceptable writing systems per Translation LLM code (a locale may legitimately
# use more than one; Serbian is written in both Cyrillic and Latin).
SCRIPT_RANGES = {
    "Cyrillic": [(0x0400, 0x04FF)], "Arabic": [(0x0600, 0x06FF), (0x0750, 0x077F)],
    "Hebrew": [(0x0590, 0x05FF)], "Devanagari": [(0x0900, 0x097F)],
    "Bengali": [(0x0980, 0x09FF)], "Gurmukhi": [(0x0A00, 0x0A7F)],
    "Gujarati": [(0x0A80, 0x0AFF)], "Tamil": [(0x0B80, 0x0BFF)],
    "Telugu": [(0x0C00, 0x0C7F)], "Kannada": [(0x0C80, 0x0CFF)],
    "Malayalam": [(0x0D00, 0x0D7F)], "Sinhala": [(0x0D80, 0x0DFF)],
    "Thai": [(0x0E00, 0x0E7F)], "Lao": [(0x0E80, 0x0EFF)],
    "Myanmar": [(0x1000, 0x109F)], "Georgian": [(0x10A0, 0x10FF)],
    "Ethiopic": [(0x1200, 0x137F)], "Khmer": [(0x1780, 0x17FF)],
    "Greek": [(0x0370, 0x03FF)], "Han": [(0x4E00, 0x9FFF), (0x3400, 0x4DBF)],
    "Japanese": [(0x3040, 0x30FF), (0x4E00, 0x9FFF)],
    "Korean": [(0xAC00, 0xD7AF), (0x1100, 0x11FF)],
    "Armenian": [(0x0530, 0x058F)], "Latin": [(0x0041, 0x005A), (0x0061, 0x007A)],
}

# Expected writing system(s) for each Translation LLM code. Multiple entries are
# all acceptable (e.g. Serbian Cyrillic or Latin).
SCRIPT_EXPECTATIONS = {
    "ru": ("Cyrillic",), "uk": ("Cyrillic",), "bg": ("Cyrillic",),
    "sr": ("Cyrillic", "Latin"), "mk": ("Cyrillic",), "be": ("Cyrillic",),
    "mn": ("Cyrillic",), "ky": ("Cyrillic",), "kk": ("Cyrillic",),
    "tg": ("Cyrillic",), "ar": ("Arabic",), "fa": ("Arabic",),
    "ur": ("Arabic",), "iw": ("Hebrew",), "he": ("Hebrew",),
    "hi": ("Devanagari",), "mr": ("Devanagari",), "ne": ("Devanagari",),
    "bn": ("Bengali",), "gu": ("Gujarati",), "pa": ("Gurmukhi",),
    "ta": ("Tamil",), "te": ("Telugu",), "kn": ("Kannada",),
    "ml": ("Malayalam",), "th": ("Thai",), "ka": ("Georgian",),
    "hy": ("Armenian",), "zh": ("Han",), "zh-CN": ("Han",), "zh-Hans": ("Han",),
    "zh-Hant": ("Han",), "zh-TW": ("Han",), "zh-HK": ("Han",),
    "ja": ("Japanese",), "ko": ("Korean",), "km": ("Khmer",), "lo": ("Lao",),
    "my": ("Myanmar",), "si": ("Sinhala",), "am": ("Ethiopic",), "el": ("Greek",),
}


def script_present(text: str, script: str) -> bool:
    for ch in text:
        cp = ord(ch)
        for lo, hi in SCRIPT_RANGES.get(script, []):
            if lo <= cp <= hi:
                return True
    return False


def any_script_present(text: str, scripts: tuple[str, ...]) -> bool:
    return any(script_present(text, s) for s in scripts)


def _ascii_digits(text: str) -> str:
    """Map any Unicode decimal digit to its ASCII equivalent."""
    out = []
    for ch in text:
        if ch.isdigit():
            try:
                out.append(str(unicodedata.digit(ch)))
                continue
            except (TypeError, ValueError):
                out.append(ch)
        else:
            out.append(ch)
    return "".join(out)


def base_language(code: str) -> str:
    return code.split("-")[0].lower()


def score(item_id: str, source_text: str, translated: str, llm_code: str,
          source: str, target: str, is_target: bool) -> dict:
    out = (translated or "").strip()
    src = (source_text or "").strip()
    flags = []
    if not out:
        flags.append("empty_output")
    if out and out == src and base_language(source) != base_language(target):
        flags.append("identical_to_source")
    ratio = (len(out) / len(src)) if src else 0.0
    if out and (ratio < 0.15 or ratio > 6.0):
        flags.append(f"length_ratio_{ratio:.2f}")
    if is_target and out:
        expected = SCRIPT_EXPECTATIONS.get(llm_code)
        if expected and not any_script_present(out, expected):
            flags.append(f"script_mismatch:{expected[0]}")
    nums = NUMERIC_EXPECTATIONS.get(item_id)
    if nums and out:
        src_digits = _ascii_digits(src)
        out_digits = _ascii_digits(out)
        src_num = "".join(ch for ch in src_digits if ch.isdigit())
        out_num = "".join(ch for ch in out_digits if ch.isdigit())
        if src_num and src_num not in out_num and not any(n in out_digits for n in nums):
            flags.append("numeric_mismatch")
    return {"flags": flags, "length_ratio": round(ratio, 3)}


CRITICAL_FLAGS = {"empty_output", "identical_to_source", "script_mismatch",
                  "numeric_mismatch"}
