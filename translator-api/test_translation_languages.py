"""Offline registry tests: no network, no paid calls.

Guards the integration contract: the backend must keep the audited mapping for
all 117 Chirp 2 locales, refuse rejected codes, and never silently substitute a
language.
"""
import csv
import sys
from pathlib import Path

API_DIR = Path(__file__).resolve().parent
REPO_ROOT = API_DIR.parent
sys.path.insert(0, str(API_DIR))

import translation_languages as registry  # noqa: E402

MATRIX_CSV = REPO_ROOT / "translation-llm-audit" / "results" / "translation-llm-language-matrix.csv"


def _audit_rows() -> list[dict]:
    with MATRIX_CSV.open(encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def test_registry_covers_all_117_locales():
    assert len(registry.CHIRP2_LOCALES) == 117
    assert len(registry.CHIRP2_TO_LLM) == 117


def test_registry_matches_audit_decisions():
    rows = {r["chirp2_code"]: r for r in _audit_rows()}
    assert set(rows) == set(registry.CHIRP2_LOCALES)
    for locale, row in rows.items():
        assert registry.CHIRP2_TO_LLM[locale] == row["translation_llm_code"], locale
        decision = row["final_decision"]
        if decision == "FAILED":
            assert not registry.is_translation_supported(locale), locale
        else:
            assert registry.is_translation_supported(locale), locale


def test_status_counts_match_audit():
    rows = _audit_rows()
    unsupported = sum(1 for r in rows if r["final_decision"] == "FAILED")
    experimental = sum(1 for r in rows if r["final_decision"] == "EXPERIMENTAL")
    retest = sum(1 for r in rows if r["final_decision"] == "RETEST_REQUIRED")
    assert len(registry.UNSUPPORTED) == unsupported == 24
    assert len(registry.EXPERIMENTAL) == experimental == 13
    assert len(registry.RETEST) == retest == 3


def test_legacy_short_codes_still_resolve():
    for short, locale in registry.LEGACY_SHORT_CODES.items():
        assert registry.resolve_translation_code(short) == registry.CHIRP2_TO_LLM[locale]


def test_unsupported_locale_never_maps_to_a_code():
    for locale in registry.UNSUPPORTED:
        assert registry.resolve_translation_code(locale) is None


def test_no_silent_substitution_for_script_variants():
    # Cantonese must not become Mandarin, Traditional must not become Simplified.
    assert registry.resolve_translation_code("yue-Hant-HK") is None
    assert registry.resolve_translation_code("cmn-Hans-CN") == "zh"
    assert registry.resolve_translation_code("cmn-Hant-TW") == "zh-TW"


def test_unknown_and_malformed_codes_are_refused():
    for bad in ("", "xx", "klingon", None, 123, "uz-UZ-extra"):
        assert registry.resolve_translation_code(bad) is None


def test_status_is_explicit_for_every_locale():
    caps = registry.capabilities()
    assert len(caps) == 117
    assert {v["status"] for v in caps.values()} <= {
        "official", "experimental", "retest", "unsupported"}


def test_all_93_frontend_locales_map_exactly_to_audited_translation_codes():
    """Prevent a silent mismatch between the working HTML picker and Translation LLM."""
    import re
    html = (REPO_ROOT / "index.html").read_text(encoding="utf-8")
    selected = re.findall(r'\\["([A-Za-z0-9-]+)","([^"]+)","(?:official|experimental|retest)"\\]', html)
    assert len(selected) == len({locale for locale, _ in selected}) == 93
    for locale, translation_code in selected:
        assert registry.resolve_translation_code(locale) == translation_code, locale
        assert registry.status_for(locale) != "unsupported", locale
