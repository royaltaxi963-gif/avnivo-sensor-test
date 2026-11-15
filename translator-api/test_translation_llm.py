"""Offline safety tests: no Google/Groq requests are made."""
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from fastapi.testclient import TestClient
from main import app

client = TestClient(app)


def test_disabled_by_default(monkeypatch):
    monkeypatch.delenv("AVNIVO_TRANSLATION_TEST_ENABLED", raising=False)
    r = client.post("/api/translate-llm-test", json={"text": "salom"})
    assert r.status_code == 503


def test_requires_token(monkeypatch):
    monkeypatch.setenv("AVNIVO_TRANSLATION_TEST_ENABLED", "true")
    monkeypatch.setenv("AVNIVO_TRANSLATION_TEST_TOKEN", "secret")
    r = client.post("/api/translate-llm-test", json={"text": "salom"})
    assert r.status_code == 401


def test_rejects_invalid_text_before_cloud_call(monkeypatch):
    monkeypatch.setenv("AVNIVO_TRANSLATION_TEST_ENABLED", "true")
    monkeypatch.setenv("AVNIVO_TRANSLATION_TEST_TOKEN", "secret")
    headers = {"x-avnivo-test-token": "secret"}
    for text in ("", "x" * 501, 123):
        r = client.post("/api/translate-llm-test", headers=headers, json={"text": text})
        assert r.status_code == 400


def test_rejects_same_language(monkeypatch):
    monkeypatch.setenv("AVNIVO_TRANSLATION_TEST_ENABLED", "true")
    monkeypatch.setenv("AVNIVO_TRANSLATION_TEST_TOKEN", "secret")
    r = client.post("/api/translate-llm-test", headers={"x-avnivo-test-token": "secret"},
                    json={"text": "salom", "source": "uz", "target": "uz"})
    assert r.status_code == 400


def test_rejects_large_payload(monkeypatch):
    monkeypatch.setenv("AVNIVO_TRANSLATION_TEST_ENABLED", "true")
    monkeypatch.setenv("AVNIVO_TRANSLATION_TEST_TOKEN", "secret")
    r = client.post("/api/translate-llm-test", headers={"x-avnivo-test-token": "secret"},
                    json={"text": "x" * 5000})
    assert r.status_code == 413
