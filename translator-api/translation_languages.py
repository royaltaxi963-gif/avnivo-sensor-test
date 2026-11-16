"""Centralized registry of Google Cloud Translation LLM language coverage.

This is the single source of truth that maps each of the 117 Chirp 2 locales
AVNIVO's speech registry exposes to a Translation LLM language code, together
with the capability status proven by the independent audit in
``translation-llm-audit/`` (6,881 real API requests against
``general/translation-llm``).

Statuses are evidence-based, never guessed:

* ``official``      -- audit PASSED: official support and quality verified.
* ``experimental``  -- Google's own table marks the language experimental.
                       Usable, but the client must show an explicit notice.
* ``retest``        -- the API accepts the code but Google does not document
                       it. Kept usable (the live API translates correctly) yet
                       flagged so the status is never overstated.
* ``unsupported``   -- the model rejected the code. Never sent to Translation
                       LLM; the language stays available for STT/TTS/offline.

The mapping mirrors ``translation-llm-audit/language_matrix.py`` exactly. A
change here must keep ``test_translation_languages.py`` green, which asserts the
registry still covers 117 locales and matches the audit decisions.

Sources (verified 2026-11-16):
* Translation LLM languages: https://cloud.google.com/translate/docs/languages
* Pricing: https://cloud.google.com/translate/pricing
"""
from __future__ import annotations

from typing import Final

MODEL_ID: Final[str] = "general/translation-llm"

# Chirp 2 locale -> Translation LLM code. Mirrors the audited mapping.
CHIRP2_TO_LLM: Final[dict[str, str]] = {
    "af-ZA": "af", "am-ET": "am", "ar-EG": "ar", "as-IN": "as", "ast-ES": "ast",
    "az-AZ": "az", "be-BY": "be", "bg-BG": "bg", "bn-BD": "bn", "bn-IN": "bn-IN",
    "bs-BA": "bs", "ca-ES": "ca", "ceb-PH": "ceb", "ckb-IQ": "ckb",
    "cmn-Hans-CN": "zh", "cmn-Hant-TW": "zh-TW", "cs-CZ": "cs", "cy-GB": "cy",
    "da-DK": "da", "de-DE": "de", "el-GR": "el", "en-AU": "en-AU",
    "en-GB": "en-GB", "en-IN": "en", "en-US": "en-US", "es-419": "es-419",
    "es-ES": "es-ES", "es-US": "es-US", "et-EE": "et", "eu-ES": "eu",
    "fa-IR": "fa", "ff-SN": "ff", "fi-FI": "fi", "fil-PH": "fil",
    "fr-CA": "fr-CA", "fr-FR": "fr", "ga-IE": "ga", "gl-ES": "gl", "gu-IN": "gu",
    "ha-NG": "ha", "hi-IN": "hi", "hr-HR": "hr", "hu-HU": "hu", "hy-AM": "hy",
    "id-ID": "id", "ig-NG": "ig", "is-IS": "is", "it-IT": "it", "iw-IL": "iw",
    "ja-JP": "ja", "jv-ID": "jv", "ka-GE": "ka", "kam-KE": "kam",
    "kea-CV": "kea", "kk-KZ": "kk", "km-KH": "km", "kn-IN": "kn", "ko-KR": "ko",
    "ky-KG": "ky", "lb-LU": "lb", "lg-UG": "lg", "ln-CD": "ln", "lo-LA": "lo",
    "lt-LT": "lt", "luo-KE": "luo", "lv-LV": "lv", "mi-NZ": "mi", "mk-MK": "mk",
    "ml-IN": "ml", "mn-MN": "mn", "mr-IN": "mr", "ms-MY": "ms", "mt-MT": "mt",
    "my-MM": "my", "ne-NP": "ne", "nl-NL": "nl", "no-NO": "no", "nso-ZA": "nso",
    "ny-MW": "ny", "oc-FR": "oc", "om-ET": "om", "or-IN": "or",
    "pa-Guru-IN": "pa", "pl-PL": "pl", "ps-AF": "ps", "pt-BR": "pt-BR",
    "pt-PT": "pt-PT", "ro-RO": "ro", "ru-RU": "ru", "rup-BG": "rup",
    "sd-IN": "sd", "si-LK": "si", "sk-SK": "sk", "sl-SI": "sl", "sn-ZW": "sn",
    "so-SO": "so", "sq-AL": "sq", "sr-RS": "sr", "su-ID": "su", "sv-SE": "sv",
    "sw": "sw", "sw-KE": "sw", "ta-IN": "ta", "te-IN": "te", "tg-TJ": "tg",
    "th-TH": "th", "tr-TR": "tr", "uk-UA": "uk", "umb-AO": "umb", "ur-PK": "ur",
    "uz-UZ": "uz", "vi-VN": "vi", "wo-SN": "wo", "xh-ZA": "xh", "yo-NG": "yo",
    "yue-Hant-HK": "yue", "zu-ZA": "zu",
}

# Locales the audit marked FAILED: the model rejects the code outright.
UNSUPPORTED: Final[frozenset[str]] = frozenset({
    "as-IN", "ast-ES", "ceb-PH", "ckb-IQ", "ff-SN", "jv-ID", "kam-KE",
    "kea-CV", "kk-KZ", "lg-UG", "luo-KE", "mi-NZ", "nso-ZA", "ny-MW",
    "oc-FR", "om-ET", "ps-AF", "rup-BG", "sd-IN", "sn-ZW", "su-ID",
    "umb-AO", "wo-SN", "yue-Hant-HK",
})

# Google's own table marks these experimental (audit class B).
EXPERIMENTAL: Final[frozenset[str]] = frozenset({
    "am-ET", "eu-ES", "ga-IE", "ha-NG", "hy-AM", "ig-NG", "lb-LU", "mn-MN",
    "mt-MT", "or-IN", "so-SO", "sr-RS", "tg-TJ",
})

# The live API translates these correctly, but Google does not document them.
# Kept usable yet flagged so support is never overstated.
RETEST: Final[frozenset[str]] = frozenset({"si-LK", "xh-ZA", "yo-NG"})

# Short codes the original four-language UI already used. They stay valid so
# existing saved selections and requests keep working unchanged.
LEGACY_SHORT_CODES: Final[dict[str, str]] = {
    "uz": "uz-UZ", "ru": "ru-RU", "en": "en-US", "tr": "tr-TR",
}

CHIRP2_LOCALES: Final[frozenset[str]] = frozenset(CHIRP2_TO_LLM)
assert len(CHIRP2_LOCALES) == 117, len(CHIRP2_LOCALES)
assert not (UNSUPPORTED & EXPERIMENTAL), "classes must be disjoint"
assert not (UNSUPPORTED & RETEST), "classes must be disjoint"
assert not (EXPERIMENTAL & RETEST), "classes must be disjoint"


def _canonical(locale: object) -> str | None:
    """Resolve a user-supplied code to a Chirp 2 locale, or None if unknown."""
    if not isinstance(locale, str):
        return None
    code = locale.strip()
    if code in CHIRP2_TO_LLM:
        return code
    return LEGACY_SHORT_CODES.get(code)


def status_for(locale: str) -> str:
    """Capability status for a canonical Chirp 2 locale."""
    if locale in UNSUPPORTED:
        return "unsupported"
    if locale in EXPERIMENTAL:
        return "experimental"
    if locale in RETEST:
        return "retest"
    return "official"


def resolve_translation_code(locale: object) -> str | None:
    """Return the Translation LLM code to send, or None when unsupported.

    None means "never call Translation LLM for this locale"; the caller must
    surface a clear error instead of substituting another language.
    """
    canonical = _canonical(locale)
    if canonical is None:
        return None
    if canonical in UNSUPPORTED:
        return None
    return CHIRP2_TO_LLM[canonical]


def is_translation_supported(locale: object) -> bool:
    return resolve_translation_code(locale) is not None


def capabilities() -> dict[str, dict[str, str]]:
    """Full per-locale capability map for the frontend and diagnostics."""
    return {
        locale: {
            "translation_code": CHIRP2_TO_LLM[locale],
            "status": status_for(locale),
        }
        for locale in sorted(CHIRP2_LOCALES)
    }
