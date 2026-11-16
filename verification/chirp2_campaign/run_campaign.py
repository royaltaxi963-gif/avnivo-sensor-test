"""AVNIVO Chirp 2 — 117-locale real-audio verification campaign.

Sends real audio to the deployed Chirp 2 endpoint, records every request as
reproducible evidence, and enforces a hard spend cap in the harness itself
(never relying on a billing alert).

Usage::

    python run_campaign.py --plan            # free: show coverage + cost estimate
    python run_campaign.py --run             # execute within the cap
    python run_campaign.py --run --locales uz-UZ,ru-RU
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import metrics  # noqa: E402
import samples  # noqa: E402

REPO = HERE.parents[1]
sys.path.insert(0, str(REPO / "translator-api"))
import chirp2_languages as registry  # noqa: E402

BASE_URL = os.getenv(
    "AVNIVO_CHIRP2_BASE_URL",
    "https://chirp2-test---avnivo-sensor-test-5xwrf7netq-ew.a.run.app",
)
EVIDENCE = HERE / "evidence.jsonl"
REPORT_DIR = HERE

# Official Speech-to-Text V2 "Standard" recognition price, first tier:
# $0.016 per minute (https://cloud.google.com/speech-to-text/pricing).
PRICE_PER_SECOND = 0.016 / 60.0
HARD_CAP_USD = 30.0
STOP_AT_USD = 25.0
MAX_SAMPLES_PER_LOCALE = 3

# FLEURS config for each Chirp 2 locale that FLEURS covers.
FLEURS_CONFIG = {
    "af-ZA": "af_za", "am-ET": "am_et", "ar-EG": "ar_eg", "as-IN": "as_in",
    "ast-ES": "ast_es", "az-AZ": "az_az", "be-BY": "be_by", "bg-BG": "bg_bg",
    "bn-IN": "bn_in", "bs-BA": "bs_ba", "ca-ES": "ca_es", "ceb-PH": "ceb_ph",
    "ckb-IQ": "ckb_iq", "cmn-Hans-CN": "cmn_hans_cn", "cs-CZ": "cs_cz",
    "cy-GB": "cy_gb", "da-DK": "da_dk", "de-DE": "de_de", "el-GR": "el_gr",
    "en-US": "en_us", "es-419": "es_419", "et-EE": "et_ee", "fa-IR": "fa_ir",
    "ff-SN": "ff_sn", "fi-FI": "fi_fi", "fil-PH": "fil_ph", "fr-FR": "fr_fr",
    "ga-IE": "ga_ie", "gl-ES": "gl_es", "gu-IN": "gu_in", "ha-NG": "ha_ng",
    "hi-IN": "hi_in", "hr-HR": "hr_hr", "hu-HU": "hu_hu", "hy-AM": "hy_am",
    "id-ID": "id_id", "ig-NG": "ig_ng", "is-IS": "is_is", "it-IT": "it_it",
    "iw-IL": "he_il", "ja-JP": "ja_jp", "jv-ID": "jv_id", "ka-GE": "ka_ge",
    "kam-KE": "kam_ke", "kea-CV": "kea_cv", "kk-KZ": "kk_kz", "km-KH": "km_kh",
    "kn-IN": "kn_in", "ko-KR": "ko_kr", "ky-KG": "ky_kg", "lb-LU": "lb_lu",
    "lg-UG": "lg_ug", "ln-CD": "ln_cd", "lo-LA": "lo_la", "lt-LT": "lt_lt",
    "luo-KE": "luo_ke", "lv-LV": "lv_lv", "mi-NZ": "mi_nz", "mk-MK": "mk_mk",
    "ml-IN": "ml_in", "mn-MN": "mn_mn", "mr-IN": "mr_in", "ms-MY": "ms_my",
    "mt-MT": "mt_mt", "my-MM": "my_mm", "ne-NP": "ne_np", "nl-NL": "nl_nl",
    "no-NO": "nb_no",
    "nso-ZA": "nso_za", "ny-MW": "ny_mw", "oc-FR": "oc_fr", "om-ET": "om_et",
    "or-IN": "or_in", "pa-Guru-IN": "pa_in", "pl-PL": "pl_pl", "ps-AF": "ps_af",
    "pt-BR": "pt_br", "ro-RO": "ro_ro", "ru-RU": "ru_ru", "sd-IN": "sd_in",
    "sk-SK": "sk_sk", "sl-SI": "sl_si", "sn-ZW": "sn_zw", "so-SO": "so_so",
    "sr-RS": "sr_rs", "sv-SE": "sv_se", "sw": "sw_ke", "sw-KE": "sw_ke", "ta-IN": "ta_in",
    "te-IN": "te_in", "tg-TJ": "tg_tj", "th-TH": "th_th", "tr-TR": "tr_tr",
    "uk-UA": "uk_ua", "umb-AO": "umb_ao", "ur-PK": "ur_pk", "uz-UZ": "uz_uz",
    "vi-VN": "vi_vn", "wo-SN": "wo_sn", "xh-ZA": "xh_za", "yo-NG": "yo_ng",
    "yue-Hant-HK": "yue_hant_hk", "zu-ZA": "zu_za",
}

# Locales FLEURS does not cover, with a fallback human source (Tatoeba ISO-639-3)
# and, only where neither exists, a synthetic espeak-ng voice.
FALLBACK = {
    "bn-BD": {"tatoeba": None, "commons": "ben", "espeak": "bn"},
    "cmn-Hant-TW": {"tatoeba": "cmn", "espeak": "cmn"},
    "en-AU": {"tatoeba": "eng", "espeak": "en"},
    "en-GB": {"tatoeba": "eng", "espeak": "en-gb"},
    "en-IN": {"tatoeba": "eng", "espeak": "en"},
    "es-ES": {"tatoeba": "spa", "espeak": "es"},
    "es-US": {"tatoeba": "spa", "espeak": "es"},
    "eu-ES": {"tatoeba": None, "commons": "eus", "espeak": "eu"},
    "fr-CA": {"tatoeba": "fra", "espeak": "fr"},
    "pt-PT": {"tatoeba": "por", "espeak": "pt"},
    "rup-BG": {"tatoeba": None, "commons": "rup", "espeak": "ro"},
    "si-LK": {"tatoeba": None, "commons": "sin", "commons_sinhala": True, "espeak": "si"},
    "sq-AL": {"tatoeba": None, "commons": "sqi", "espeak": "sq"},
    "su-ID": {"tatoeba": None, "commons": "sun", "espeak": "id"},
    "sw": {"tatoeba": None, "commons": "swh", "espeak": "sw"},
}

# Neutral, easy sentences for the synthetic fallback. Kept short and generic so
# a synthetic score is never mistaken for a human-speech score.
ESPEAK_SENTENCE = {
    "bn": "এটি একটি পরীক্ষা",
    "cmn": "这是一个测试",
    "en": "this is a test of the speech recognition system",
    "en-gb": "this is a test of the speech recognition system",
    "es": "esta es una prueba del sistema de reconocimiento de voz",
    "eu": "hau ahotsa ezagutzeko sistemaren proba bat da",
    "fr": "ceci est un test du système de reconnaissance vocale",
    "pt": "este é um teste do sistema de reconhecimento de voz",
    "ro": "acesta este un test al sistemului de recunoaștere vocală",
    "si": "මෙය කථන හඳුනාගැනීමේ පද්ධතියේ පරීක්ෂණයකි",
    "sq": "ky është një test i sistemit të njohjes së të folurit",
    "id": "ini adalah tes sistem pengenalan suara",
    "sw": "hii ni jaribio la mfumo wa kutambua sauti",
}


def _post(locale: str, path: Path, index: int) -> tuple[int, str, float]:
    blob = path.read_bytes()
    boundary = uuid.uuid4().hex
    head = (
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\n{locale}\r\n"
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"audio\"; "
        f"filename=\"s.wav\"\r\nContent-Type: audio/wav\r\n\r\n"
    ).encode()
    body = head + blob + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(
        BASE_URL + "/api/transcribe-google",
        data=body,
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "x-forwarded-for": f"198.18.{index // 250}.{index % 250}",
        },
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            payload = response.read().decode("utf-8", "replace")
            status = response.status
    except urllib.error.HTTPError as error:
        payload = error.read().decode("utf-8", "replace")
        status = error.code
    except Exception as error:  # network failure
        payload = json.dumps({"error": f"{type(error).__name__}: {error}"})
        status = 0
    return status, payload, round(time.perf_counter() - started, 3)


def _gather(locale: str, count: int) -> list[dict]:
    if locale in FLEURS_CONFIG:
        try:
            found = samples.fleurs_samples(FLEURS_CONFIG[locale], locale, count)
            if found:
                return found
        except Exception as error:
            print(f"  ! FLEURS {locale}: {type(error).__name__}: {error}")
    fallback = FALLBACK.get(locale, {})
    if fallback.get("tatoeba"):
        try:
            found = samples.tatoeba_samples(fallback["tatoeba"], locale, count)
            if found:
                return found
        except Exception as error:
            print(f"  ! Tatoeba {locale}: {type(error).__name__}: {error}")
    if fallback.get("commons"):
        try:
            found = samples.commons_samples(
                fallback["commons"], locale, count,
                sinhala=bool(fallback.get("commons_sinhala")),
            )
            if found:
                return found
        except Exception as error:
            print(f"  ! Commons {locale}: {type(error).__name__}: {error}")
    voice = fallback.get("espeak")
    if voice:
        sentence = ESPEAK_SENTENCE.get(voice, "this is a test")
        try:
            return samples.espeak_samples(voice, locale, [sentence], count)
        except Exception as error:
            print(f"  ! espeak {locale}: {type(error).__name__}: {error}")
    return []


def load_evidence() -> list[dict]:
    if not EVIDENCE.exists():
        return []
    rows = []
    for line in EVIDENCE.read_text(encoding="utf-8").splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows


def spend(rows: list[dict]) -> float:
    return round(sum(r.get("billable_seconds", 0) for r in rows) * PRICE_PER_SECOND, 4)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan", action="store_true")
    parser.add_argument("--run", action="store_true")
    parser.add_argument("--locales", default="")
    parser.add_argument("--per-locale", type=int, default=MAX_SAMPLES_PER_LOCALE)
    args = parser.parse_args()

    locales = [c.strip() for c in args.locales.split(",") if c.strip()] or sorted(registry.CHIRP2_LOCALES)
    unknown = [c for c in locales if not registry.is_supported_locale(c)]
    if unknown:
        print("not Chirp 2 locales:", unknown)
        return 2

    if args.plan:
        covered = sum(1 for c in locales if c in FLEURS_CONFIG)
        print(f"locales: {len(locales)}")
        print(f"FLEURS human coverage: {covered}")
        print(f"fallback (Tatoeba human / espeak synthetic): {len(locales) - covered}")
        worst = len(locales) * args.per_locale * 20 * PRICE_PER_SECOND
        print(f"worst-case cost at 20s/sample, {args.per_locale}/locale: ${worst:.2f}")
        print(f"hard cap: ${HARD_CAP_USD:.2f}, stop-at: ${STOP_AT_USD:.2f}")
        return 0

    if not args.run:
        parser.print_help()
        return 1

    rows = load_evidence()
    done = {(r["locale"], r["sample_sha256"]) for r in rows if r.get("sample_sha256")}
    total = spend(rows)
    print(f"existing evidence: {len(rows)} samples, ${total:.4f} spent")

    for locale in locales:
        if total >= STOP_AT_USD:
            print(f"STOP: spend ${total:.4f} reached the ${STOP_AT_USD:.2f} safety threshold")
            break
        print(f"[{locale}]")
        try:
            found = _gather(locale, args.per_locale)
        except Exception as error:
            print(f"  ! gather failed: {type(error).__name__}: {error}")
            found = []
        if not found:
            missing = {"locale": locale, "status": "NOT_TESTED", "http_status": None,
                       "reason": "no licensed audio sample available"}
            rows.append(missing)
            with EVIDENCE.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(missing, ensure_ascii=False) + "\n")
            print("  NOT_TESTED (no sample)")
            continue
        for index, sample in enumerate(found):
            path = Path(sample["audio"])
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            if (locale, digest) in done:
                print("  = cached")
                continue
            if total >= STOP_AT_USD:
                print("  STOP: budget threshold reached mid-locale")
                break
            status, payload, latency = _post(locale, path, len(rows))
            try:
                data = json.loads(payload)
            except ValueError:
                data = {"text": ""}
            text = str(data.get("text", "")) if status == 200 else ""
            billable = sample["duration_seconds"]
            total = round(total + billable * PRICE_PER_SECOND, 6)
            row = {
                "locale": locale,
                "sample_sha256": digest,
                "sample": path.name,
                "transcript": sample["transcript"],
                "source": sample["source"],
                "source_detail": sample["source_detail"],
                "license": sample["license"],
                "synthetic": sample["synthetic"],
                "duration_seconds": billable,
                "billable_seconds": billable,
                "http_status": status,
                "hypothesis": text,
                "latency_seconds": latency,
                "model": data.get("model"),
                "error": None if status == 200 else payload[:300],
                "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            }
            if status == 200:
                row.update(metrics.score(locale, sample["transcript"], text))
                row["status"] = "PASS" if text.strip() else "FAIL"
            else:
                row["status"] = "FAIL"
            rows.append(row)
            done.add((locale, digest))
            with EVIDENCE.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(row, ensure_ascii=False) + "\n")
            print(f"  {row['status']} http={status} wer={row.get('wer')} "
                  f"lat={latency}s ${total:.4f}")

    print(f"TOTAL spend ${total:.4f} of ${HARD_CAP_USD:.2f}")
    if total > HARD_CAP_USD:
        print("BUDGET EXCEEDED")
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
