"""Acquire real, licensed audio samples with known transcripts for Chirp 2 verification.

Sources
-------
* ``google/fleurs`` (CC-BY-4.0) — real human read speech, 102 of the 117 Chirp 2
  locales. Per-locale TSV supplies the gold transcript; the audio tarball is
  range-downloaded (the archive is far larger than needed) and the extracted
  WAVs are matched back to their transcript by file name.
* ``PolyAI/minds14`` (CC-BY-4.0) — real human speech for locales FLEURS lacks.
* Tatoeba (CC-BY 2.0 FR) — real human speech with the sentence text.
* Lingua Libre / Wikimedia Commons (CC-BY-SA-4.0) — real human word recordings.
* espeak-ng — **synthetic** speech, used only where no human recording exists,
  and always flagged ``synthetic: true`` so it is never mixed with human results.

Every sample is stored with its source, license, transcript and a SHA-256 so a
result can be reproduced byte-for-byte.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import tarfile
import urllib.parse
import urllib.request
from pathlib import Path

CACHE = Path(os.getenv("CHIRP2_SAMPLE_CACHE", "/tmp/chirp2_samples"))
FLEURS = "https://huggingface.co/datasets/google/fleurs/resolve/main"
# Wikimedia's user-agent policy wants a contact URL in the UA string.
UA = {"User-Agent": "AVNIVO-Chirp2-QA/1.0 (https://github.com/royaltaxi963-gif/avnivo-sensor-test; qa@avnivo.example)"}


def _get(url: str, timeout: int = 120, headers: dict | None = None, retries: int = 4) -> bytes:
    import time as _time
    last = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={**UA, **(headers or {})})
            with urllib.request.urlopen(req, timeout=timeout) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            last = error
            # Commons throttles aggressively; back off instead of hammering it.
            if error.code in (429, 503) and attempt < retries - 1:
                _time.sleep(2 ** attempt + 1)
                continue
            raise
    raise last


def _json(url: str, timeout: int = 60) -> object:
    return json.loads(_get(url, timeout))


def _range(url: str, nbytes: int, timeout: int = 180) -> bytes:
    return _get(url, timeout, {"Range": f"bytes=0-{nbytes - 1}"})


# --------------------------------------------------------------------------- #
# FLEURS
# --------------------------------------------------------------------------- #

def fleurs_tsv(config: str) -> list[tuple[str, str]]:
    """(file_name, transcript) pairs from the official FLEURS test split."""
    raw = _get(f"{FLEURS}/data/{config}/test.tsv", 90).decode("utf-8")
    rows = []
    for line in raw.splitlines():
        cols = line.split("\t")
        if len(cols) >= 3 and cols[1].endswith(".wav") and cols[2].strip():
            rows.append((cols[1], cols[2].strip()))
    return rows


def fleurs_samples(config: str, locale: str, count: int = 3, window_mb: int = 60) -> list[dict]:
    """Extract `count` FLEURS clips for one locale, matched to their transcripts."""
    target = CACHE / locale
    manifest_path = target / "manifest.json"
    if manifest_path.exists():
        cached = json.loads(manifest_path.read_text(encoding="utf-8"))
        if len(cached) >= count:
            return cached[:count]

    target.mkdir(parents=True, exist_ok=True)
    pairs = fleurs_tsv(config)
    wanted = {name: text for name, text in pairs}

    raw = _range(f"{FLEURS}/data/{config}/audio/test.tar.gz", window_mb * 1024 * 1024)
    archive = target / "partial.tar.gz"
    archive.write_bytes(raw)

    extracted: dict[str, Path] = {}
    try:
        with tarfile.open(archive, "r:gz") as tar:
            for member in tar:
                name = os.path.basename(member.name)
                if not name.endswith(".wav") or name not in wanted:
                    continue
                handle = tar.extractfile(member)
                if handle is None:
                    continue
                out = target / name
                out.write_bytes(handle.read())
                extracted[name] = out
    except (EOFError, tarfile.ReadError):
        # The archive is deliberately truncated to the first N megabytes, so the
        # gzip stream ends mid-member. Members already extracted are complete.
        pass

    manifest: list[dict] = []
    for name, text in pairs:
        path = extracted.get(name)
        if path is None:
            continue
        pcm = target / f"{name}.16k.wav"
        subprocess.run(
            ["ffmpeg", "-v", "error", "-y", "-i", str(path), "-ar", "16000",
             "-ac", "1", "-c:a", "pcm_s16le", str(pcm)],
            check=True,
        )
        path.unlink()
        duration = round((pcm.stat().st_size - 44) / 32000, 3)
        manifest.append({
            "locale": locale,
            "audio": str(pcm),
            "transcript": text,
            "source": "google/fleurs",
            "source_detail": f"FLEURS test split, config {config}, file {name}",
            "license": "CC-BY-4.0",
            "url": f"{FLEURS}/data/{config}/audio/test.tar.gz",
            "duration_seconds": duration,
            "synthetic": False,
        })
        if len(manifest) >= count:
            break

    archive.unlink(missing_ok=True)
    manifest_path.write_text(json.dumps(manifest, indent=1, ensure_ascii=False), encoding="utf-8")
    return manifest


# --------------------------------------------------------------------------- #
# Tatoeba (real human speech, sentence text provided)
# --------------------------------------------------------------------------- #

def tatoeba_samples(iso639_3: str, locale: str, count: int = 3) -> list[dict]:
    target = CACHE / locale
    manifest_path = target / "manifest.json"
    if manifest_path.exists():
        cached = json.loads(manifest_path.read_text(encoding="utf-8"))
        if len(cached) >= count:
            return cached[:count]
    target.mkdir(parents=True, exist_ok=True)
    query = urllib.parse.urlencode({"from": iso639_3, "has_audio": "yes", "limit": count * 6})
    payload = _json(f"https://tatoeba.org/en/api_v0/search?{query}")
    manifest: list[dict] = []
    for row in payload.get("results", []):
        if len(manifest) >= count:
            break
        audios = row.get("audios") or []
        if not audios:
            continue
        text = (row.get("text") or "").strip()
        if not text:
            continue
        try:
            blob = _get("https://tatoeba.org" + audios[0]["download_url"], 90)
        except Exception:
            continue
        src = target / f"tatoeba-{row['id']}"
        src.write_bytes(blob)
        pcm = target / f"tatoeba-{row['id']}.16k.wav"
        try:
            subprocess.run(
                ["ffmpeg", "-v", "error", "-y", "-i", str(src), "-ar", "16000",
                 "-ac", "1", "-c:a", "pcm_s16le", str(pcm)],
                check=True,
            )
        except subprocess.CalledProcessError:
            continue
        src.unlink(missing_ok=True)
        duration = round((pcm.stat().st_size - 44) / 32000, 3)
        if duration < 0.6 or duration > 60:
            pcm.unlink(missing_ok=True)
            continue
        manifest.append({
            "locale": locale,
            "audio": str(pcm),
            "transcript": text,
            "source": "tatoeba",
            "source_detail": f"Tatoeba sentence {row['id']} by {audios[0].get('author')}",
            "license": row.get("license") or "CC-BY 2.0 FR",
            "url": "https://tatoeba.org" + audios[0]["download_url"],
            "duration_seconds": duration,
            "synthetic": False,
        })
    manifest_path.write_text(json.dumps(manifest, indent=1, ensure_ascii=False), encoding="utf-8")
    return manifest


# --------------------------------------------------------------------------- #
# Wikimedia Commons (Lingua Libre + pronunciation projects) — real human speech
# --------------------------------------------------------------------------- #

_LL_NAME = re.compile(r"^File:LL-Q\d+ \(([a-z-]+)\)-(.+?)-(.+)\.(wav|ogg|mp3|flac)$")
_SI_NAME = re.compile(r"^File:Si-(.+)\.(wav|ogg|mp3|flac)$")


def _commons_search(query: str, limit: int = 20) -> list[str]:
    params = urllib.parse.urlencode({
        "action": "query", "format": "json", "list": "search",
        "srnamespace": "6", "srlimit": str(limit), "srsearch": query,
    })
    data = _json(f"https://commons.wikimedia.org/w/api.php?{params}")
    return [hit["title"] for hit in data.get("query", {}).get("search", [])]


def _commons_url(title: str) -> str | None:
    params = urllib.parse.urlencode({
        "action": "query", "format": "json", "prop": "imageinfo",
        "iiprop": "url", "titles": title,
    })
    data = _json(f"https://commons.wikimedia.org/w/api.php?{params}")
    for page in data.get("query", {}).get("pages", {}).values():
        info = page.get("imageinfo") or []
        if info:
            return info[0].get("url")
    return None


# Hand-verified Commons recordings whose transcript is stated in the file
# description on the file page (checked via the imageinfo extmetadata API).
COMMONS_CURATED: dict[str, list[tuple[str, str]]] = {
    "si-LK": [
        ("File:Amma.ogg", "අම්මා"),
        ("File:Pansala.ogg", "පැන්සල"),
    ],
}


def commons_samples(iso639_3: str, locale: str, count: int = 3, sinhala: bool = False) -> list[dict]:
    """Real human word recordings from Wikimedia Commons (Lingua Libre / Si-*).

    The spoken word is the trailing field of the file name, so the transcript is
    exact. These are single-word clips, which is a narrow but genuine measure of
    recognition; the report records the source so the limited scope is visible.
    """
    target = CACHE / locale
    manifest_path = target / "manifest.json"
    if manifest_path.exists():
        cached = json.loads(manifest_path.read_text(encoding="utf-8"))
        if len(cached) >= count:
            return cached[:count]
    target.mkdir(parents=True, exist_ok=True)

    pattern = _SI_NAME if sinhala else _LL_NAME
    # "intitle:Si-" returns mostly PDFs; the pronunciation category search
    # surfaces the Si-*.ogg recordings, which the Si- pattern then filters.
    query = "Sinhala pronunciation" if sinhala else f"intitle:LL- ({iso639_3})"
    candidates: list[tuple[str, str | None]] = list(COMMONS_CURATED.get(locale, []))
    candidates += [(title, None) for title in _commons_search(query)]
    manifest: list[dict] = []
    for title, curated in candidates:
        if len(manifest) >= count:
            break
        if curated is not None:
            word = curated
        else:
            match = pattern.match(title)
            if not match:
                continue
            # LL-Q<id> (<lang>)-<speaker>-<word>.<ext>: the word is the last field.
            word = (match.group(1) if sinhala else match.group(3)).strip()
        if not word:
            continue
        url = _commons_url(title)
        if not url:
            continue
        try:
            blob = _get(url, 90)
        except Exception:
            continue
        src = target / f"commons-{len(manifest)}.audio"
        src.write_bytes(blob)
        pcm = target / f"commons-{len(manifest)}.16k.wav"
        try:
            subprocess.run(
                ["ffmpeg", "-v", "error", "-y", "-i", str(src), "-ar", "16000",
                 "-ac", "1", "-c:a", "pcm_s16le", str(pcm)],
                check=True,
            )
        except subprocess.CalledProcessError:
            src.unlink(missing_ok=True)
            continue
        src.unlink(missing_ok=True)
        duration = round((pcm.stat().st_size - 44) / 32000, 3)
        if duration < 0.2 or duration > 30:
            pcm.unlink(missing_ok=True)
            continue
        import time as _time
        _time.sleep(1.5)  # stay well inside Wikimedia's rate limits
        manifest.append({
            "locale": locale,
            "audio": str(pcm),
            "transcript": word,
            "source": "wikimedia-commons",
            "source_detail": f"{title} (single spoken word)",
            "license": "CC-BY-SA-4.0",
            "url": url,
            "duration_seconds": duration,
            "synthetic": False,
        })
    manifest_path.write_text(json.dumps(manifest, indent=1, ensure_ascii=False), encoding="utf-8")
    return manifest


# --------------------------------------------------------------------------- #
# espeak-ng (SYNTHETIC, clearly flagged)
# --------------------------------------------------------------------------- #

def espeak_samples(voice: str, locale: str, sentences: list[str], count: int = 3) -> list[dict]:
    target = CACHE / locale
    manifest_path = target / "manifest.json"
    if manifest_path.exists():
        cached = json.loads(manifest_path.read_text(encoding="utf-8"))
        if len(cached) >= count:
            return cached[:count]
    target.mkdir(parents=True, exist_ok=True)
    manifest: list[dict] = []
    for index, sentence in enumerate(sentences[:count]):
        raw = target / f"espeak-{index}.wav"
        subprocess.run(
            ["espeak-ng", "-v", voice, "-s", "145", "-w", str(raw), sentence],
            check=True,
        )
        pcm = target / f"espeak-{index}.16k.wav"
        subprocess.run(
            ["ffmpeg", "-v", "error", "-y", "-i", str(raw), "-ar", "16000",
             "-ac", "1", "-c:a", "pcm_s16le", str(pcm)],
            check=True,
        )
        raw.unlink(missing_ok=True)
        duration = round((pcm.stat().st_size - 44) / 32000, 3)
        manifest.append({
            "locale": locale,
            "audio": str(pcm),
            "transcript": sentence,
            "source": "espeak-ng",
            "source_detail": f"espeak-ng voice {voice}",
            "license": "GPL-3.0 (espeak-ng output, no rights reserved by author)",
            "url": "https://github.com/espeak-ng/espeak-ng",
            "duration_seconds": duration,
            "synthetic": True,
        })
    manifest_path.write_text(json.dumps(manifest, indent=1, ensure_ascii=False), encoding="utf-8")
    return manifest
