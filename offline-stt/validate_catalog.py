"""Validate AVNIVO offline STT catalog without downloading untrusted packages."""
import json
import re
import sys
from pathlib import Path

LANGUAGES = {"uz-UZ", "ru-RU", "en-US", "hi-IN", "bn-BD", "tr-TR", "tk-TM"}
SHA256 = re.compile(r"^[0-9a-f]{64}$")


def validate(data):
    if data.get("schema_version") != 1:
        raise ValueError("Unsupported catalog schema")
    languages = data.get("languages")
    if not isinstance(languages, list) or len(languages) != len(LANGUAGES):
        raise ValueError("Expected exactly seven language entries")
    seen = set()
    ids = set()
    for entry in languages:
        tag = entry["language_tag"]
        if tag not in LANGUAGES or tag in seen:
            raise ValueError(f"Unsupported or duplicate language: {tag}")
        seen.add(tag)
        package_id = entry["id"]
        if package_id != "stt-" + tag.split("-")[0] or package_id in ids:
            raise ValueError(f"Wrong package ID for {tag}")
        ids.add(package_id)
        status = entry["status"]
        if status not in {"unavailable", "candidate", "available"}:
            raise ValueError(f"Invalid status for {tag}")
        artifacts = entry.get("artifacts", [])
        if status == "available" and len(artifacts) != 1:
            raise ValueError(f"Available pack must have exactly one artifact: {tag}")
        if status != "available" and artifacts:
            raise ValueError(f"Unverified pack must not publish artifacts: {tag}")
        for artifact in artifacts:
            required = ("download_url", "bytes", "sha256", "model_family", "model_version", "runtime", "runtime_version", "license_id", "license_url", "benchmark_url")
            if any(not artifact.get(k) for k in required):
                raise ValueError(f"Incomplete artifact: {tag}")
            if not artifact["download_url"].startswith("https://"):
                raise ValueError(f"Non-HTTPS download: {tag}")
            if type(artifact["bytes"]) is not int or artifact["bytes"] <= 0:
                raise ValueError(f"Invalid size: {tag}")
            if not SHA256.fullmatch(artifact["sha256"]):
                raise ValueError(f"Invalid SHA256: {tag}")
    return True


if __name__ == "__main__":
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).with_name("catalog.json")
    validate(json.loads(path.read_text(encoding="utf-8")))
    print("Offline STT catalog validation passed")
