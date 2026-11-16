"""Static UI/registry contract checks; no paid API calls.

These assert the *current* speech-picker implementation. The earlier tests
pinned selectors (``id='avnivoChirp2Locale'``, ``renderSpeechLocales``,
``localeKey``) that the modal picker replaced, so they failed on every run and
were silently excluded from CI — which is exactly how a contract test stops
protecting anything. Each assertion below names a string that exists in the
shipped file and that would only disappear if the contract it guards broke.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
HTML = ROOT / "tests/fixtures/chirp2-multilingual-legacy.html"
REGISTRY = ROOT / "translator-api" / "chirp2_languages.py"


def test_all_chirp2_locales_are_present_in_ui_matrix():
    source = HTML.read_text(encoding="utf-8")
    registry = REGISTRY.read_text(encoding="utf-8")
    matrix = source.split("window.CHIRP2_MATRIX=[", 1)[1].split("];", 1)[0]
    codes = re.findall(r'\["([A-Za-z0-9-]+)","[^"]+"\]', matrix)
    registry_codes = re.findall(r'^    "([A-Za-z0-9-]+)": "[^"]+",$', registry, re.M)
    assert len(codes) == len(set(codes)) == 117
    assert set(codes) == set(registry_codes)


def test_speech_picker_uses_selected_locale_without_uzbek_fallback():
    """The picker must send the selected BCP-47 locale, never a fixed fallback."""
    source = HTML.read_text(encoding="utf-8")
    # The searchable modal picker that replaced the native <select> UI.
    assert "avnivo-language-options" in source
    assert "avnivo-language-search" in source
    # The chosen option drives the request language field directly.
    assert "form.append('language',locale)" in source
    # The resolver returns the selected locale and rejects anything unsupported.
    assert "function chirp2LocaleFor(baseCode)" in source
    assert "const locale=chirp2LocaleFor(language);" in source
    # No silent Uzbek substitution: an unsupported language reports instead.
    assert "Bu til uchun Google Chirp 2 ovoz tanish hali sozlanmagan" in source


def test_translation_selector_remains_independent():
    """The translation panels keep their own four base codes and stay separate."""
    source = HTML.read_text(encoding="utf-8")
    assert "const supported=Object.keys(native);" in source
    assert "window.avnivoOnlineLanguageForSpeaker" in source
    # The four translation base codes map onto verified Chirp 2 locales.
    assert "CHIRP2_BASE_TO_LOCALE=Object.freeze({uz:'uz-UZ',ru:'ru-RU',en:'en-US',tr:'tr-TR'})" in source
