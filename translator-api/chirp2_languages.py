"""Centralized registry of Google Chirp 2 (Speech-to-Text V2) locale coverage.

Single source of truth shared by the backend recognition endpoint and the
frontend language routing. Adding an officially supported language is a data
change in this registry, never a copy of the recognition engine.

Generated from the official Google Cloud "Cloud Speech-to-Text V2 supported
languages" table (model column = ``chirp_2``). See
``docs/chirp2-language-support.md`` for sources and the verification date.
"""
from __future__ import annotations

from typing import Final

# Google Cloud region hosting Chirp 2. The backend calls the matching regional
# Speech endpoint, so the region here must stay in CHIRP2_REGIONS.
GOOGLE_REGION: Final[str] = "us-central1"
GOOGLE_MODEL: Final[str] = "chirp_2"

# Regions where the chirp_2 model is offered, per the official documentation.
CHIRP2_REGIONS: Final[frozenset[str]] = frozenset(
    {"us-central1", "europe-west4", "asia-southeast1"}
)

# Every BCP-47 locale officially supported by chirp_2 for Recognize.
CHIRP2_LOCALE_NAMES: Final[dict[str, str]] = {
    "af-ZA": "Afrikaans (South Africa)",
    "am-ET": "Amharic (Ethiopia)",
    "ar-EG": "Arabic (Egypt)",
    "as-IN": "Assamese (India)",
    "ast-ES": "Asturian (Spain)",
    "az-AZ": "Azerbaijani (Azerbaijan)",
    "be-BY": "Belarusian (Belarus)",
    "bg-BG": "Bulgarian (Bulgaria)",
    "bn-BD": "Bengali (Bangladesh)",
    "bn-IN": "Bengali (India)",
    "bs-BA": "Bosnian (Bosnia and Herzegovina)",
    "ca-ES": "Catalan (Spain)",
    "ceb-PH": "Cebuano (Philippines)",
    "ckb-IQ": "Central Kurdish (Iraq)",
    "cmn-Hans-CN": "Chinese (Simplified, China)",
    "cmn-Hant-TW": "Chinese, Mandarin (Traditional, Taiwan)",
    "cs-CZ": "Czech (Czech Republic)",
    "cy-GB": "Welsh (United Kingdom)",
    "da-DK": "Danish (Denmark)",
    "de-DE": "German (Germany)",
    "el-GR": "Greek (Greece)",
    "en-AU": "English (Australia)",
    "en-GB": "English (United Kingdom)",
    "en-IN": "English (India)",
    "en-US": "English (United States)",
    "es-419": "Spanish (Latin American)",
    "es-ES": "Spanish (Spain)",
    "es-US": "Spanish (United States)",
    "et-EE": "Estonian (Estonia)",
    "eu-ES": "Basque (Spain)",
    "fa-IR": "Persian (Iran)",
    "ff-SN": "Fulah (Senegal)",
    "fi-FI": "Finnish (Finland)",
    "fil-PH": "Filipino (Philippines)",
    "fr-CA": "French (Canada)",
    "fr-FR": "French (France)",
    "ga-IE": "Irish (Ireland)",
    "gl-ES": "Galician (Spain)",
    "gu-IN": "Gujarati (India)",
    "ha-NG": "Hausa (Nigeria)",
    "hi-IN": "Hindi (India)",
    "hr-HR": "Croatian (Croatia)",
    "hu-HU": "Hungarian (Hungary)",
    "hy-AM": "Armenian (Armenia)",
    "id-ID": "Indonesian (Indonesia)",
    "ig-NG": "Igbo (Nigeria)",
    "is-IS": "Icelandic (Iceland)",
    "it-IT": "Italian (Italy)",
    "iw-IL": "Hebrew (Israel)",
    "ja-JP": "Japanese (Japan)",
    "jv-ID": "Javanese (Indonesia)",
    "ka-GE": "Georgian (Georgia)",
    "kam-KE": "Kamba (Kenya)",
    "kea-CV": "Kabuverdianu (Cape Verde)",
    "kk-KZ": "Kazakh (Kazakhstan)",
    "km-KH": "Khmer (Cambodia)",
    "kn-IN": "Kannada (India)",
    "ko-KR": "Korean (South Korea)",
    "ky-KG": "Kyrgyz (Cyrillic)",
    "lb-LU": "Luxembourgish (Luxembourg)",
    "lg-UG": "Ganda (Uganda)",
    "ln-CD": "Lingala (Congo-Kinshasa)",
    "lo-LA": "Lao (Laos)",
    "lt-LT": "Lithuanian (Lithuania)",
    "luo-KE": "Luo (Kenya)",
    "lv-LV": "Latvian (Latvia)",
    "mi-NZ": "Maori (New Zealand)",
    "mk-MK": "Macedonian (North Macedonia)",
    "ml-IN": "Malayalam (India)",
    "mn-MN": "Mongolian (Mongolia)",
    "mr-IN": "Marathi (India)",
    "ms-MY": "Malay (Malaysia)",
    "mt-MT": "Maltese (Malta)",
    "my-MM": "Burmese (Myanmar)",
    "ne-NP": "Nepali (Nepal)",
    "nl-NL": "Dutch (Netherlands)",
    "no-NO": "Norwegian Bokmål (Norway)",
    "nso-ZA": "Sepedi (South Africa)",
    "ny-MW": "Nyanja (Malawi)",
    "oc-FR": "Occitan (France)",
    "om-ET": "Oromo (Ethiopia)",
    "or-IN": "Oriya (India)",
    "pa-Guru-IN": "Punjabi (Gurmukhi India)",
    "pl-PL": "Polish (Poland)",
    "ps-AF": "Pashto",
    "pt-BR": "Portuguese (Brazil)",
    "pt-PT": "Portuguese (Portugal)",
    "ro-RO": "Romanian (Romania)",
    "ru-RU": "Russian (Russia)",
    "rup-BG": "Aromanian (Bulgaria)",
    "sd-IN": "Sindhi (India)",
    "si-LK": "Sinhala (Sri Lanka)",
    "sk-SK": "Slovak (Slovakia)",
    "sl-SI": "Slovenian (Slovenia)",
    "sn-ZW": "Shona (Zimbabwe)",
    "so-SO": "Somali",
    "sq-AL": "Albanian (Albania)",
    "sr-RS": "Serbian (Serbia)",
    "su-ID": "Sundanese (Indonesia)",
    "sv-SE": "Swedish (Sweden)",
    "sw": "Swahili",
    "sw-KE": "Swahili (Kenya)",
    "ta-IN": "Tamil (India)",
    "te-IN": "Telugu (India)",
    "tg-TJ": "Tajik (Tajikistan)",
    "th-TH": "Thai (Thailand)",
    "tr-TR": "Turkish (Turkey)",
    "uk-UA": "Ukrainian (Ukraine)",
    "umb-AO": "Umbundu (Angola)",
    "ur-PK": "Urdu (Pakistan)",
    "uz-UZ": "Uzbek (Uzbekistan)",
    "vi-VN": "Vietnamese (Vietnam)",
    "wo-SN": "Wolof (Senegal)",
    "xh-ZA": "Xhosa (South Africa)",
    "yo-NG": "Yoruba (Nigeria)",
    "yue-Hant-HK": "Chinese, Cantonese (Traditional Hong Kong)",
    "zu-ZA": "Zulu (South Africa)",
}

CHIRP2_LOCALES: Final[frozenset[str]] = frozenset(CHIRP2_LOCALE_NAMES)

# Recognized-words hint applied only to the protected Uzbek baseline. Other
# locales intentionally carry no adaptation so their behaviour stays default.
CHIRP2_ADAPTATION: Final[dict[str, list[dict[str, object]]]] = {
    "uz-UZ": [
        {"value": "Avnivo", "boost": 20.0},
        {"value": "ovoz tanish", "boost": 15.0},
        {"value": "sinovi", "boost": 10.0},
    ],
}

# Sync Recognize accepts at most 10 MB or 1 minute of audio, whichever is hit
# first. The byte cap is enforced by the endpoint; the duration cap is enforced
# by keeping the client recording short.
CHIRP2_SYNC_MAX_BYTES: Final[int] = 10 * 1024 * 1024
CHIRP2_SYNC_MAX_SECONDS: Final[int] = 60


def is_supported_locale(locale: object) -> bool:
    """Return True only for an exact, officially supported BCP-47 Chirp 2 code."""
    return isinstance(locale, str) and locale in CHIRP2_LOCALES


def locale_name(locale: str) -> str:
    """Human readable name for a supported locale (empty string if unknown)."""
    return CHIRP2_LOCALE_NAMES.get(locale, "")


def adaptation_phrases(locale: str) -> list[dict[str, object]] | None:
    """Per-locale phrase hints, or None when the locale uses default settings."""
    phrases = CHIRP2_ADAPTATION.get(locale)
    return [dict(phrase) for phrase in phrases] if phrases else None
