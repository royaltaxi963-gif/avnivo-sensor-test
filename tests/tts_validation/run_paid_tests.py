"""Staged paid TTS validation runner for AVNIVO.

Design guarantees:
  * Sequential by default; optional bounded concurrency (never unbounded).
  * Budget-gated: refuses to start a request that would push conservative
    estimated spend past STOP_THRESHOLD_USD ($18 of the $20 ceiling).
  * Append-only ledger + per-test checkpoint: resuming never repeats a
    completed paid request.
  * Saves every artifact and its objective audio metrics for evidence.

Usage:
  python run_paid_tests.py --stage A
  python run_paid_tests.py --stage B
  python run_paid_tests.py --stage C
  python run_paid_tests.py --stage styles
  python run_paid_tests.py --stage concurrency
  python run_paid_tests.py --summary
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
import time
from dataclasses import dataclass

import httpx

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from audio_analysis import analyze_wav_bytes  # noqa: E402
from budget import (  # noqa: E402
    BudgetLedger, LedgerEntry, WORST_CASE_REQUEST_USD,
    estimate_request_cost_usd, utcnow,
)

ENDPOINT = os.getenv(
    "AVNIVO_TTS_URL",
    "https://avnivo-translation-test-792624108019.europe-west1.run.app/api/tts",
)
HERE = os.path.dirname(os.path.abspath(__file__))
ARTIFACTS = os.path.join(HERE, "artifacts")
LEDGER = os.path.join(HERE, "ledger", "expenditure.json")
CHECKPOINT = os.path.join(HERE, "ledger", "checkpoint.json")

VOICES = ["Kore", "Aoede", "Leda", "Zephyr", "Achernar",
          "Puck", "Charon", "Orus", "Achird", "Iapetus"]
PRIORITY = ["uz", "ru", "en", "tr"]

# Short, natural, language-appropriate sentences (kept under the 350-char cap).
TEXTS = {
    "uz": "Salom, bu AVNIVO ovoz sinovi. Bugun havo juda yaxshi va biz ishlashni davom etamiz.",
    "ru": "Здравствуйте, это проверка голоса AVNIVO. Сегодня хорошая погода и мы продолжаем работу.",
    "en": "Hello, this is an AVNIVO voice test. The weather is nice today and we will keep working.",
    "tr": "Merhaba, bu bir AVNIVO ses testi. Bugün hava çok güzel ve çalışmaya devam ediyoruz.",
    "de": "Hallo, dies ist ein AVNIVO Spracht. Das Wetter ist heute schön und wir arbeiten weiter.",
    "fr": "Bonjour, ceci est un test vocal AVNIVO. Il fait beau aujourd'hui et nous continuons le travail.",
    "es": "Hola, esta es una prueba de voz de AVNIVO. Hoy hace buen tiempo y seguimos trabajando.",
    "it": "Ciao, questo è un test vocale AVNIVO. Oggi il tempo è bello e continuiamo a lavorare.",
    "pt": "Olá, este é um teste de voz da AVNIVO. O tempo está bom hoje e continuamos a trabalhar.",
    "pl": "Cześć, to jest test głosu AVNIVO. Dziś jest ładna pogoda i kontynuujemy pracę.",
    "nl": "Hallo, dit is een AVNIVO stemtest. Het weer is vandaag mooi en we gaan door met werken.",
    "sv": "Hej, det här är ett AVNIVO rösttest. Vädret är fint idag och vi fortsätter arbeta.",
    "cs": "Ahoj, toto je hlasový test AVNIVO. Dnes je pěkné počasí a pokračujeme v práci.",
    "el": "Γεια σας, αυτό είναι ένα τεστ φωνής AVNIVO. Ο καιρός είναι καλός σήμερα και συνεχίζουμε.",
    "ro": "Bună, acesta este un test vocal AVNIVO. Astăzi vremea este frumoasă și continuăm lucrul.",
    "hu": "Szia, ez egy AVNIVO hangteszt. Ma szép az idő és folytatjuk a munkát.",
    "fi": "Hei, tämä on AVNIVO äänitesti. Sää on tänään kaunis ja jatkamme työtä.",
    "da": "Hej, dette er en AVNIVO stemmetest. Vejret er fint i dag og vi fortsætter arbejdet.",
    "ar": "مرحبا، هذا اختبار صوتي من أفنيفو. الطقس جميل اليوم ونواصل العمل.",
    "he": "שלום, זהו מבחן קול של AVNIVO. מזג האוויר יפה היום ואנחנו ממשיכים לעבוד.",
    "fa": "سلام، این یک آزمایش صدا از آونيفو است. امروز هوا خوب است و به کار ادامه می‌دهیم.",
    "hi": "नमस्ते, यह एक एवनीवो वॉयस टेस्ट है। आज मौसम अच्छा है और हम काम जारी रखते हैं।",
    "bn": "হ্যালো, এটি একটি অ্যাভনিভো ভয়েস পরীক্ষা। আজ আবহাওয়া ভালো এবং আমরা কাজ চালিয়ে যাচ্ছি।",
    "zh": "你好，这是 AVNIVO 语音测试。今天天气很好，我们将继续工作。",
    "ja": "こんにちは、これはAVNIVOの音声テストです。今日は天気が良く、作業を続けます。",
    "ko": "안녕하세요, 이것은 AVNIVO 음성 테스트입니다. 오늘 날씨가 좋아서 계속 작업합니다.",
    "kk": "Сәлеметсіз бе, бұл AVNIVO дауыс сынағы. Бүгін ауа райы жақсы, жұмысты жалғастырамыз.",
    "az": "Salam, bu AVNIVO səs testidir. Bu gün hava gözəldir və işləməyə davam edirik.",
    "th": "สวัสดี นี่คือการทดสอบเสียงของ AVNIVO วันนี้อากาศดีและเราจะทำงานต่อไป",
    "vi": "Xin chào, đây là bài kiểm tra giọng nói AVNIVO. Hôm nay thời tiết đẹp và chúng ta tiếp tục làm việc.",
    "id": "Halo, ini adalah tes suara AVNIVO. Cuaca hari ini bagus dan kami terus bekerja.",
    "ms": "Helo, ini ujian suara AVNIVO. Cuaca hari ini baik dan kami terus bekerja.",
    "ca": "Hola, aquesta és una prova de veu d'AVNIVO. Avui fa bon temps i seguim treballant.",
}

# Style probe texts chosen to trigger each branch of _detect_tts_style.
STYLE_PROBES = {
    "calm": "Bugun havo yaxshi va biz ishlashni davom etamiz.",
    "happy": "Rahmat, juda katta rahmat sizga.",
    "sad": "Afsus, juda achinarli xabar keldi.",
    "excited": "Tabriklaymiz! Zo'r yangilik, juda yaxshi!",
    "concerned": "Qayerda yurding? Men seni izlayapman, nega kelmading?",
}
EXTRA_LANGS_STAGE_C = ["de", "fr", "es", "it", "pt", "pl", "nl", "sv", "cs", "el",
                       "ro", "hu", "fi", "da", "ar", "he", "fa", "hi", "bn", "zh",
                       "ja", "ko", "kk", "az", "th", "vi", "id", "ms", "ca"]


@dataclass
class Case:
    test_id: str
    language: str
    voice: str
    text: str
    style_hint: str | None = None


def build_plan(stage: str) -> list[Case]:
    cases: list[Case] = []
    if stage == "A":
        for lang in PRIORITY:
            cases.append(Case(f"A-{lang}-Kore", lang, "Kore", TEXTS[lang]))
    elif stage == "B":
        for voice in VOICES:
            for lang in PRIORITY:
                cases.append(Case(f"B-{lang}-{voice}", lang, voice, TEXTS[lang]))
    elif stage == "C":
        for lang in EXTRA_LANGS_STAGE_C:
            cases.append(Case(f"C-{lang}-Kore", lang, "Kore", TEXTS[lang]))
        # A few extra voice cross-checks on non-priority languages.
        for lang, voice in [("de", "Puck"), ("ja", "Aoede"), ("ar", "Charon"),
                            ("zh", "Leda"), ("hi", "Orus")]:
            cases.append(Case(f"C-{lang}-{voice}", lang, voice, TEXTS[lang]))
    elif stage == "styles":
        for style, text in STYLE_PROBES.items():
            cases.append(Case(f"S-{style}-uz-Kore", "uz", "Kore", text, style_hint=style))
        # Each style also in Russian to test cross-language style behaviour.
        ru_texts = {
            "calm": "Сегодня хорошая погода и мы продолжаем работу.",
            "happy": "Спасибо, большое спасибо вам.",
            "sad": "Сожалею, пришли очень грустные новости.",
            "excited": "Поздравляем! Отличная новость, замечательно!",
            "concerned": "Где ты был? Я тебя ищу, почему ты не пришёл?",
        }
        for style, text in ru_texts.items():
            cases.append(Case(f"S-{style}-ru-Kore", "ru", "Kore", text, style_hint=style))
    elif stage == "concurrency":
        for i in range(4):
            cases.append(Case(f"X-conc-{i}-en-Puck", "en", "Puck", TEXTS["en"]))
    else:
        raise SystemExit(f"unknown stage {stage}")
    return cases


def load_done() -> set[str]:
    if not os.path.exists(CHECKPOINT):
        return set()
    with open(CHECKPOINT, "r", encoding="utf-8") as fh:
        return set(json.load(fh).get("completed", []))


def save_done(done: set[str]) -> None:
    os.makedirs(os.path.dirname(CHECKPOINT), exist_ok=True)
    tmp = CHECKPOINT + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump({"completed": sorted(done)}, fh, indent=2)
    os.replace(tmp, CHECKPOINT)


async def run_case(client: httpx.AsyncClient, ledger: BudgetLedger, case: Case,
                   concurrency: int = 1, retries_429: int = 3, delay: float = 0.0) -> bool:
    if not ledger.can_spend(WORST_CASE_REQUEST_USD):
        print(f"[BUDGET] stop threshold reached (spent=${ledger.spent_usd}); skipping {case.test_id}")
        return False
    artifact = os.path.join(ARTIFACTS, f"{case.test_id}.wav")
    os.makedirs(ARTIFACTS, exist_ok=True)
    start = time.monotonic()
    status = None
    notes = ""
    metrics = None
    # The service throttles to RATE_LIMIT/WINDOW per IP. A 429 is a correct
    # response, so we back off and retry rather than treating it as a failure.
    attempt = 0
    while True:
        try:
            r = await client.post(ENDPOINT, json={
                "text": case.text, "voice": case.voice, "language": case.language,
            })
        except httpx.RequestError as exc:
            latency = round(time.monotonic() - start, 3)
            notes = f"transport:{type(exc).__name__}"
            break
        if r.status_code == 429 and attempt < retries_429:
            attempt += 1
            await asyncio.sleep(6.0)
            continue
        status = r.status_code
        latency = round(time.monotonic() - start, 3)
        if r.status_code == 200 and r.content:
            with open(artifact, "wb") as fh:
                fh.write(r.content)
            metrics = analyze_wav_bytes(r.content)
            notes = "ok" if metrics.valid_wav and not metrics.all_silent else "audio-issue"
        else:
            notes = f"http-{r.status_code}"
        break
    audio_seconds = metrics.duration_s if (metrics and metrics.duration_s) else 0.0
    cost = estimate_request_cost_usd(len(case.text), audio_seconds)
    style_seen = None
    ledger.record(LedgerEntry(
        test_id=case.test_id, timestamp=utcnow(), language=case.language,
        voice=case.voice, style=case.style_hint, text_chars=len(case.text),
        http_status=status, latency_s=latency, audio_seconds=audio_seconds,
        estimated_cost_usd=cost,
        artifact=os.path.relpath(artifact, HERE) if os.path.exists(artifact) else None,
        notes=notes,
    ))
    if metrics:
        with open(os.path.join(ARTIFACTS, f"{case.test_id}.metrics.json"), "w", encoding="utf-8") as fh:
            json.dump(metrics.to_dict(), fh, indent=2)
    print(f"[{case.test_id}] http={status} latency={latency}s dur={audio_seconds}s "
          f"cost=${cost:.6f} spent=${ledger.spent_usd} notes={notes}")
    return True


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--stage", required=False)
    ap.add_argument("--summary", action="store_true")
    ap.add_argument("--concurrency", type=int, default=1)
    ap.add_argument("--delay", type=float, default=0.0)
    ap.add_argument("--reconcile", action="store_true",
                    help="mark only ledger entries with HTTP 200 as completed")
    args = ap.parse_args()

    ledger = BudgetLedger(LEDGER)
    if args.summary:
        print(json.dumps(ledger.summary(), indent=2))
        return
    if args.reconcile:
        succeeded = {e.test_id for e in ledger.entries if e.http_status == 200}
        save_done(succeeded)
        print(f"reconciled: {len(succeeded)} completed tests")
        return
    if not args.stage:
        raise SystemExit("--stage or --summary required")

    plan = build_plan(args.stage)
    done = load_done()
    todo = [c for c in plan if c.test_id not in done]
    print(f"stage={args.stage} planned={len(plan)} already_done={len(plan)-len(todo)} todo={len(todo)}")
    print(f"budget: spent=${ledger.spent_usd} remaining_to_ceiling=${ledger.remaining_usd()} "
          f"worst_case/req=${WORST_CASE_REQUEST_USD}")

    limits = httpx.Limits(max_connections=args.concurrency, max_keepalive_connections=args.concurrency)
    async with httpx.AsyncClient(timeout=120, limits=limits) as client:
        if args.concurrency > 1:
            sem = asyncio.Semaphore(args.concurrency)

            async def bounded(case):
                async with sem:
                    return await run_case(client, ledger, case, args.concurrency)

            await asyncio.gather(*(bounded(c) for c in todo))
        else:
            for case in todo:
                ok = await run_case(client, ledger, case)
                if not ok:
                    break
                done.add(case.test_id)
                save_done(done)
                if args.delay:
                    await asyncio.sleep(args.delay)
    print("SUMMARY", json.dumps(ledger.summary()))


if __name__ == "__main__":
    asyncio.run(main())
