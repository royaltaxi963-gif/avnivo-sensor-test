"""Static UI/registry contract checks; no paid API calls."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
HTML = ROOT / "avnivo-v11-49-chirp2-multilingual.html"
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
    source = HTML.read_text(encoding="utf-8")
    assert "id='avnivoChirp2Locale'" in source
    assert "localeSearch.addEventListener('input',renderSpeechLocales)" in source
    assert "const locale=selectedLocale;" in source
    assert "form.append('language',locale)" in source
    assert "localStorage.setItem(localeKey,selectedLocale)" in source


def test_translation_selector_remains_independent():
    source = HTML.read_text(encoding="utf-8")
    assert "const supported=Object.keys(native);" in source
    assert "window.avnivoOnlineLanguageForSpeaker" in source
    assert "Bu tanlov faqat ovozni matnga aylantiradi" in source
