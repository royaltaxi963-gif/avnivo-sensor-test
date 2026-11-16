"""WER/CER/accuracy metrics with explicit, documented normalisation rules.

Normalisation (applied identically to the reference and the hypothesis)
-----------------------------------------------------------------------
1. Unicode NFKC normalisation (folds compatibility forms, e.g. full-width Latin).
2. Case folding (``str.casefold``) — recognition is not expected to reproduce
   capitalisation.
3. Punctuation and symbol removal (Unicode categories P* and S*), because Chirp 2
   punctuation is advisory and locale-dependent.
4. Removal of combining diacritics that are *not* part of the base letter is
   **not** done: diacritics are meaningful in many locales (e.g. tr-TR, vi-VN),
   so removing them would flatter the score. Only NFC/NFKC composition is used.
5. Whitespace collapsed to single spaces and stripped.

Tokenisation for WER
--------------------
* Space-delimited scripts: tokens are whitespace-separated words.
* Scripts that do not delimit words with spaces (Chinese, Japanese, Thai, Khmer,
  Lao, Burmese) are **not** word-segmentable without a dictionary. For those
  locales WER is computed over **characters** (one token per character), and the
  report marks ``wer_is_char_level: true`` so WER and CER are directly comparable
  there. This is stated in the report rather than hidden.

Metrics
-------
* ``wer`` — (S + D + I) / N over the token stream.
* ``cer`` — same edit-distance ratio over the character stream.
* ``word_accuracy`` — ``max(0, 1 - wer)``; reported alongside the raw WER so the
  two are never confused.
* ``exact_match`` — normalised hypothesis equals normalised reference.
* ``substitutions`` / ``deletions`` / ``insertions`` — counted explicitly so
  dropped and hallucinated words are visible.
"""
from __future__ import annotations

import re
import unicodedata

import jiwer

# Locales whose orthography does not separate words with spaces. WER is computed
# at character level for these (see module docstring).
NO_SPACE_LOCALES = frozenset({
    "cmn-Hans-CN", "cmn-Hant-TW", "yue-Hant-HK", "ja-JP",
    "th-TH", "km-KH", "lo-LA", "my-MM",
})

_PUNCT = re.compile(r"[\u2000-\u206f\u2e00-\u2e7f!-/:-@\[-`{-~\u3000-\u303f\uff00-\uffef]", re.UNICODE)
_WS = re.compile(r"\s+")


def normalise(text: str) -> str:
    """Apply the documented normalisation chain."""
    if text is None:
        return ""
    text = unicodedata.normalize("NFKC", text)
    text = text.casefold()
    kept = []
    for char in text:
        category = unicodedata.category(char)
        if category.startswith("P") or category.startswith("S") or category in ("Cf", "Cc", "Co", "Cs"):
            # Keep whitespace (Zs is separator-space, still allowed) and letters.
            if char.isspace():
                kept.append(" ")
            continue
        kept.append(char)
    text = "".join(kept)
    return _WS.sub(" ", text).strip()


def _tokens(text: str, char_level: bool) -> list[str]:
    return list(text) if char_level else text.split()


def score(locale: str, reference: str, hypothesis: str) -> dict:
    """Compute every metric for one sample."""
    ref = normalise(reference)
    hyp = normalise(hypothesis)
    char_level = locale in NO_SPACE_LOCALES

    ref_tokens = _tokens(ref, char_level)
    hyp_tokens = _tokens(hyp, char_level)

    if not ref_tokens:
        # A reference with no comparable content cannot be scored.
        return {
            "wer": None, "cer": None, "word_accuracy": None, "exact_match": False,
            "substitutions": None, "deletions": None, "insertions": None,
            "wer_is_char_level": char_level, "reference_normalised": ref,
            "hypothesis_normalised": hyp, "reference_tokens": 0,
        }

    if char_level:
        # No word delimiters: token-level and character-level coincide.
        counts = jiwer.process_characters(ref, hyp)
        wer = counts.cer
        cer = counts.cer
    else:
        counts = jiwer.process_words(ref, hyp)
        wer = counts.wer
        cer = jiwer.process_characters(ref, hyp).cer
    return {
        "wer": round(wer, 4),
        "cer": round(cer, 4),
        "word_accuracy": round(max(0.0, 1.0 - wer), 4),
        "exact_match": ref == hyp,
        "substitutions": int(counts.substitutions),
        "deletions": int(counts.deletions),
        "insertions": int(counts.insertions),
        "wer_is_char_level": char_level,
        "reference_normalised": ref,
        "hypothesis_normalised": hyp,
        "reference_tokens": len(ref_tokens),
    }
