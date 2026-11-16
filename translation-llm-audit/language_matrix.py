"""Chirp 2 -> Google Cloud Translation LLM language mapping and classification.

This module is part of an INDEPENDENT audit harness. It never imports the
AVNIVO application and never modifies it. It answers one question: for each of
the 117 BCP-47 locales that AVNIVO's Chirp 2 speech registry exposes, does the
official Google Cloud Translation LLM model (``general/translation-llm``)
support translating text, and under which class.

Sources (verified 2026-11-16):
* Translation LLM supported languages table:
  https://cloud.google.com/translate/docs/languages
* Chirp 2 (Speech-to-Text V2) locale registry:
  translator-api/chirp2_languages.py (AVNIVO, read-only)

Classification rules (never infer support from NMT):
  A  officially supported by Translation LLM
  B  experimental per Google's own table
  C  not present in the Translation LLM table (NMT-only or absent)
  D  undetermined (needs a live probe to decide)
"""
from __future__ import annotations

from typing import Final

SOURCE_LANGUAGES_URL: Final[str] = "https://cloud.google.com/translate/docs/languages"
PRICING_URL: Final[str] = "https://cloud.google.com/translate/pricing"
MODEL_ID: Final[str] = "general/translation-llm"
DEFAULT_REGION: Final[str] = "us-central1"
DEFAULT_PROJECT: Final[str] = "project-6bcf7fe6-cb55-49b7-9f4"
VERIFIED_ON: Final[str] = "2026-11-16"

# Translation LLM "Official Support" column (language codes), parsed verbatim
# from the official table on SOURCE_LANGUAGES_URL.
LLM_OFFICIAL: Final[frozenset[str]] = frozenset({
    "af", "ar", "ar-SA", "az", "be", "bg", "bn", "bn-IN", "bs", "bs-Cyrl", "ca",
    "cs", "cy", "da", "de", "el", "en", "en-AU", "en-CA", "en-GB", "en-NZ",
    "en-PH", "en-US", "en-ZA", "es", "es-419", "es-AR", "es-CL", "es-CO",
    "es-CR", "es-EC", "es-ES", "es-GT", "es-HN", "es-HT", "es-MX", "es-NI",
    "es-PA", "es-PE", "es-PR", "es-PY", "es-SV", "es-US", "es-UY", "es-VE",
    "et", "fa", "fi", "fil", "fr", "fr-CA", "fr-CH", "fy", "gl", "gn", "gu",
    "he", "hi", "hr", "hu", "id", "is", "it", "iw", "ja", "ka", "km", "kn",
    "ko", "ky", "ln", "lo", "lt", "lv", "mk", "ml", "mr", "ms", "my", "nb",
    "ne", "nl", "nl-BE", "no", "pa", "pa-PK", "pl", "pt", "pt-BR", "pt-PT",
    "ro", "ru", "sk", "sl", "sq", "sv", "sw", "ta", "te", "th", "tl", "tr",
    "uk", "ur", "uz", "vi", "zh", "zh-CN", "zh-HK", "zh-Hans", "zh-Hant",
    "zh-TW", "zu",
})

# Translation LLM "Experimental Support" column, parsed verbatim.
LLM_EXPERIMENTAL: Final[frozenset[str]] = frozenset({
    "am", "eu", "ga", "gd", "ha", "hy", "ig", "lb", "mn", "mt", "or", "so",
    "sr", "tg",
})

# The 117 Chirp 2 locales AVNIVO exposes, read from chirp2_languages.py.
CHIRP2_LOCALES: Final[tuple[str, ...]] = (
    "af-ZA", "am-ET", "ar-EG", "as-IN", "ast-ES", "az-AZ", "be-BY", "bg-BG",
    "bn-BD", "bn-IN", "bs-BA", "ca-ES", "ceb-PH", "ckb-IQ", "cmn-Hans-CN",
    "cmn-Hant-TW", "cs-CZ", "cy-GB", "da-DK", "de-DE", "el-GR", "en-AU",
    "en-GB", "en-IN", "en-US", "es-419", "es-ES", "es-US", "et-EE", "eu-ES",
    "fa-IR", "ff-SN", "fi-FI", "fil-PH", "fr-CA", "fr-FR", "ga-IE", "gl-ES",
    "gu-IN", "ha-NG", "hi-IN", "hr-HR", "hu-HU", "hy-AM", "id-ID", "ig-NG",
    "is-IS", "it-IT", "iw-IL", "ja-JP", "jv-ID", "ka-GE", "kam-KE", "kea-CV",
    "kk-KZ", "km-KH", "kn-IN", "ko-KR", "ky-KG", "lb-LU", "lg-UG", "ln-CD",
    "lo-LA", "lt-LT", "luo-KE", "lv-LV", "mi-NZ", "mk-MK", "ml-IN", "mn-MN",
    "mr-IN", "ms-MY", "mt-MT", "my-MM", "ne-NP", "nl-NL", "no-NO", "nso-ZA",
    "ny-MW", "oc-FR", "om-ET", "or-IN", "pa-Guru-IN", "pl-PL", "ps-AF",
    "pt-BR", "pt-PT", "ro-RO", "ru-RU", "rup-BG", "sd-IN", "si-LK", "sk-SK",
    "sl-SI", "sn-ZW", "so-SO", "sq-AL", "sr-RS", "su-ID", "sv-SE", "sw",
    "sw-KE", "ta-IN", "te-IN", "tg-TJ", "th-TH", "tr-TR", "uk-UA", "umb-AO",
    "ur-PK", "uz-UZ", "vi-VN", "wo-SN", "xh-ZA", "yo-NG", "yue-Hant-HK",
    "zu-ZA",
)

# Human-readable names, mirroring chirp2_languages.py (read-only copy).
CHIRP2_LOCALE_NAMES: Final[dict[str, str]] = {
    "af-ZA": "Afrikaans (South Africa)", "am-ET": "Amharic (Ethiopia)",
    "ar-EG": "Arabic (Egypt)", "as-IN": "Assamese (India)",
    "ast-ES": "Asturian (Spain)", "az-AZ": "Azerbaijani (Azerbaijan)",
    "be-BY": "Belarusian (Belarus)", "bg-BG": "Bulgarian (Bulgaria)",
    "bn-BD": "Bengali (Bangladesh)", "bn-IN": "Bengali (India)",
    "bs-BA": "Bosnian (Bosnia and Herzegovina)", "ca-ES": "Catalan (Spain)",
    "ceb-PH": "Cebuano (Philippines)", "ckb-IQ": "Central Kurdish (Iraq)",
    "cmn-Hans-CN": "Chinese (Simplified, China)",
    "cmn-Hant-TW": "Chinese, Mandarin (Traditional, Taiwan)",
    "cs-CZ": "Czech (Czech Republic)", "cy-GB": "Welsh (United Kingdom)",
    "da-DK": "Danish (Denmark)", "de-DE": "German (Germany)",
    "el-GR": "Greek (Greece)", "en-AU": "English (Australia)",
    "en-GB": "English (United Kingdom)", "en-IN": "English (India)",
    "en-US": "English (United States)", "es-419": "Spanish (Latin American)",
    "es-ES": "Spanish (Spain)", "es-US": "Spanish (United States)",
    "et-EE": "Estonian (Estonia)", "eu-ES": "Basque (Spain)",
    "fa-IR": "Persian (Iran)", "ff-SN": "Fulah (Senegal)",
    "fi-FI": "Finnish (Finland)", "fil-PH": "Filipino (Philippines)",
    "fr-CA": "French (Canada)", "fr-FR": "French (France)",
    "ga-IE": "Irish (Ireland)", "gl-ES": "Galician (Spain)",
    "gu-IN": "Gujarati (India)", "ha-NG": "Hausa (Nigeria)",
    "hi-IN": "Hindi (India)", "hr-HR": "Croatian (Croatia)",
    "hu-HU": "Hungarian (Hungary)", "hy-AM": "Armenian (Armenia)",
    "id-ID": "Indonesian (Indonesia)", "ig-NG": "Igbo (Nigeria)",
    "is-IS": "Icelandic (Iceland)", "it-IT": "Italian (Italy)",
    "iw-IL": "Hebrew (Israel)", "ja-JP": "Japanese (Japan)",
    "jv-ID": "Javanese (Indonesia)", "ka-GE": "Georgian (Georgia)",
    "kam-KE": "Kamba (Kenya)", "kea-CV": "Kabuverdianu (Cape Verde)",
    "kk-KZ": "Kazakh (Kazakhstan)", "km-KH": "Khmer (Cambodia)",
    "kn-IN": "Kannada (India)", "ko-KR": "Korean (South Korea)",
    "ky-KG": "Kyrgyz (Cyrillic)", "lb-LU": "Luxembourgish (Luxembourg)",
    "lg-UG": "Ganda (Uganda)", "ln-CD": "Lingala (Congo-Kinshasa)",
    "lo-LA": "Lao (Laos)", "lt-LT": "Lithuanian (Lithuania)",
    "luo-KE": "Luo (Kenya)", "lv-LV": "Latvian (Latvia)",
    "mi-NZ": "Maori (New Zealand)", "mk-MK": "Macedonian (North Macedonia)",
    "ml-IN": "Malayalam (India)", "mn-MN": "Mongolian (Mongolia)",
    "mr-IN": "Marathi (India)", "ms-MY": "Malay (Malaysia)",
    "mt-MT": "Maltese (Malta)", "my-MM": "Burmese (Myanmar)",
    "ne-NP": "Nepali (Nepal)", "nl-NL": "Dutch (Netherlands)",
    "no-NO": "Norwegian Bokmal (Norway)", "nso-ZA": "Sepedi (South Africa)",
    "ny-MW": "Nyanja (Malawi)", "oc-FR": "Occitan (France)",
    "om-ET": "Oromo (Ethiopia)", "or-IN": "Oriya (India)",
    "pa-Guru-IN": "Punjabi (Gurmukhi India)", "pl-PL": "Polish (Poland)",
    "ps-AF": "Pashto", "pt-BR": "Portuguese (Brazil)",
    "pt-PT": "Portuguese (Portugal)", "ro-RO": "Romanian (Romania)",
    "ru-RU": "Russian (Russia)", "rup-BG": "Aromanian (Bulgaria)",
    "sd-IN": "Sindhi (India)", "si-LK": "Sinhala (Sri Lanka)",
    "sk-SK": "Slovak (Slovakia)", "sl-SI": "Slovenian (Slovenia)",
    "sn-ZW": "Shona (Zimbabwe)", "so-SO": "Somali", "sq-AL": "Albanian (Albania)",
    "sr-RS": "Serbian (Serbia)", "su-ID": "Sundanese (Indonesia)",
    "sv-SE": "Swedish (Sweden)", "sw": "Swahili", "sw-KE": "Swahili (Kenya)",
    "ta-IN": "Tamil (India)", "te-IN": "Telugu (India)",
    "tg-TJ": "Tajik (Tajikistan)", "th-TH": "Thai (Thailand)",
    "tr-TR": "Turkish (Turkey)", "uk-UA": "Ukrainian (Ukraine)",
    "umb-AO": "Umbundu (Angola)", "ur-PK": "Urdu (Pakistan)",
    "uz-UZ": "Uzbek (Uzbekistan)", "vi-VN": "Vietnamese (Vietnam)",
    "wo-SN": "Wolof (Senegal)", "xh-ZA": "Xhosa (South Africa)",
    "yo-NG": "Yoruba (Nigeria)",
    "yue-Hant-HK": "Chinese, Cantonese (Traditional Hong Kong)",
    "zu-ZA": "Zulu (South Africa)",
}

# Chirp 2 locale -> Translation LLM code. Each entry records the mapping basis
# so regional-variant decisions are auditable instead of guessed.
#   exact   : the identical code exists in the Translation LLM table
#   base    : mapped to the base language of the same language
#   script  : mapped to a script-specific code that preserves the writing system
CHIRP2_TO_LLM: Final[dict[str, tuple[str, str]]] = {
    "af-ZA": ("af", "base"), "am-ET": ("am", "base"), "ar-EG": ("ar", "base"),
    "as-IN": ("as", "base"), "ast-ES": ("ast", "base"), "az-AZ": ("az", "base"),
    "be-BY": ("be", "base"), "bg-BG": ("bg", "base"), "bn-BD": ("bn", "base"),
    "bn-IN": ("bn-IN", "exact"), "bs-BA": ("bs", "base"), "ca-ES": ("ca", "base"),
    "ceb-PH": ("ceb", "base"), "ckb-IQ": ("ckb", "base"),
    "cmn-Hans-CN": ("zh", "script"), "cmn-Hant-TW": ("zh-TW", "script"),
    "cs-CZ": ("cs", "base"), "cy-GB": ("cy", "base"), "da-DK": ("da", "base"),
    "de-DE": ("de", "base"), "el-GR": ("el", "base"), "en-AU": ("en-AU", "exact"),
    "en-GB": ("en-GB", "exact"), "en-IN": ("en", "base"),
    "en-US": ("en-US", "exact"), "es-419": ("es-419", "exact"),
    "es-ES": ("es-ES", "exact"), "es-US": ("es-US", "exact"),
    "et-EE": ("et", "base"), "eu-ES": ("eu", "base"), "fa-IR": ("fa", "base"),
    "ff-SN": ("ff", "base"), "fi-FI": ("fi", "base"), "fil-PH": ("fil", "base"),
    "fr-CA": ("fr-CA", "exact"), "fr-FR": ("fr", "base"),
    "ga-IE": ("ga", "base"), "gl-ES": ("gl", "base"), "gu-IN": ("gu", "base"),
    "ha-NG": ("ha", "base"), "hi-IN": ("hi", "base"), "hr-HR": ("hr", "base"),
    "hu-HU": ("hu", "base"), "hy-AM": ("hy", "base"), "id-ID": ("id", "base"),
    "ig-NG": ("ig", "base"), "is-IS": ("is", "base"), "it-IT": ("it", "base"),
    "iw-IL": ("iw", "exact"), "ja-JP": ("ja", "base"), "jv-ID": ("jv", "base"),
    "ka-GE": ("ka", "base"), "kam-KE": ("kam", "base"), "kea-CV": ("kea", "base"),
    "kk-KZ": ("kk", "base"), "km-KH": ("km", "base"), "kn-IN": ("kn", "base"),
    "ko-KR": ("ko", "base"), "ky-KG": ("ky", "base"), "lb-LU": ("lb", "base"),
    "lg-UG": ("lg", "base"), "ln-CD": ("ln", "base"), "lo-LA": ("lo", "base"),
    "lt-LT": ("lt", "base"), "luo-KE": ("luo", "base"), "lv-LV": ("lv", "base"),
    "mi-NZ": ("mi", "base"), "mk-MK": ("mk", "base"), "ml-IN": ("ml", "base"),
    "mn-MN": ("mn", "base"), "mr-IN": ("mr", "base"), "ms-MY": ("ms", "base"),
    "mt-MT": ("mt", "base"), "my-MM": ("my", "base"), "ne-NP": ("ne", "base"),
    "nl-NL": ("nl", "base"), "no-NO": ("no", "base"), "nso-ZA": ("nso", "base"),
    "ny-MW": ("ny", "base"), "oc-FR": ("oc", "base"), "om-ET": ("om", "base"),
    "or-IN": ("or", "base"), "pa-Guru-IN": ("pa", "base"), "pl-PL": ("pl", "base"),
    "ps-AF": ("ps", "base"), "pt-BR": ("pt-BR", "exact"),
    "pt-PT": ("pt-PT", "exact"), "ro-RO": ("ro", "base"), "ru-RU": ("ru", "base"),
    "rup-BG": ("rup", "base"), "sd-IN": ("sd", "base"), "si-LK": ("si", "base"),
    "sk-SK": ("sk", "base"), "sl-SI": ("sl", "base"), "sn-ZW": ("sn", "base"),
    "so-SO": ("so", "base"), "sq-AL": ("sq", "base"), "sr-RS": ("sr", "base"),
    "su-ID": ("su", "base"), "sv-SE": ("sv", "base"), "sw": ("sw", "base"),
    "sw-KE": ("sw", "base"), "ta-IN": ("ta", "base"), "te-IN": ("te", "base"),
    "tg-TJ": ("tg", "base"), "th-TH": ("th", "base"), "tr-TR": ("tr", "base"),
    "uk-UA": ("uk", "base"), "umb-AO": ("umb", "base"), "ur-PK": ("ur", "base"),
    "uz-UZ": ("uz", "base"), "vi-VN": ("vi", "base"), "wo-SN": ("wo", "base"),
    "xh-ZA": ("xh", "base"), "yo-NG": ("yo", "base"),
    "yue-Hant-HK": ("yue", "base"), "zu-ZA": ("zu", "base"),
}

assert len(CHIRP2_LOCALES) == 117, len(CHIRP2_LOCALES)
assert set(CHIRP2_LOCALES) == set(CHIRP2_TO_LLM), "mapping must cover every locale"
assert not (LLM_OFFICIAL & LLM_EXPERIMENTAL), "classes must be disjoint"


def classify(llm_code: str) -> str:
    """Return the documented class for a mapped Translation LLM code."""
    if llm_code in LLM_OFFICIAL:
        return "A"
    if llm_code in LLM_EXPERIMENTAL:
        return "B"
    return "C"


def matrix_rows() -> list[dict]:
    """One deterministic row per Chirp 2 locale, in registry order."""
    rows = []
    for index, code in enumerate(CHIRP2_LOCALES, start=1):
        llm_code, basis = CHIRP2_TO_LLM[code]
        cls = classify(llm_code)
        rows.append({
            "order": index,
            "chirp2_code": code,
            "language_name": CHIRP2_LOCALE_NAMES[code],
            "translation_llm_code": llm_code,
            "regional_variant": basis,
            "official_support": cls == "A",
            "experimental": cls == "B",
            "documented_class": cls,
        })
    return rows


if __name__ == "__main__":
    from collections import Counter

    rows = matrix_rows()
    counts = Counter(r["documented_class"] for r in rows)
    print(f"locales: {len(rows)}")
    print(f"A (official): {counts['A']}")
    print(f"B (experimental): {counts['B']}")
    print(f"C (unsupported): {counts['C']}")
