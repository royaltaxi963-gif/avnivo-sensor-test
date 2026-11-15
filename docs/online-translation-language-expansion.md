# AVNIVO Online translation language expansion — isolated plan

Scope: **text translation only**, not Google Chirp 2 STT, TTS, Offline STT, or UI redesign.

## Verified repository baseline (2026-11-15)
- Backend: `translator-api/main.py`, `POST /api/translate`, explicitly accepts only `uz, ru, en, tr` and rejects same-language pairs.
- Backend calls Google Cloud Translation Advanced v3 `general/translation-llm` in configured `us-central1` or `global`.
- Frontend: `avnivo-v11-47-groq-sdk-exact-code-test.html`, online selector `native` and `names` tables contain the same four languages.
- Browser posts `{text,source,target}` to the translation service endpoint.
- OpenHands is concurrently working on Chirp 2 STT. **Do not edit shared HTML/backend until integration coordination.**

## Next acceptance gates
1. Obtain official **Translation LLM** language support documentation for the configured region/model (not the general Cloud Translation language list, and not Chirp 2 STT list).
2. Create a canonical translation-only language registry, each entry: BCP-47 code, native display name, names in other selected languages, provider support evidence, region, verification state.
3. Confirm model support for both translation directions; do not assume symmetric coverage.
4. Test backend requests in isolation with mocked provider responses and negative cases. Real provider calls require explicit budget approval.
5. Expand backend allowlist and frontend language picker together only after coordination with the OpenHands branch. Ensure reciprocal names and persisted selections work.
6. Run browser regression for language picker, translation, microphone, voice output, settings, and Offline mode. Do not claim tests without logs.
7. Preserve current production until PR review and deploy approval.

## Initial translation-language candidates — NOT YET VERIFIED FOR TRANSLATION LLM
Arabic (ar), Hindi (hi), Bengali (bn), French (fr), German (de), Spanish (es), Chinese Simplified (zh-CN), Japanese (ja), Korean (ko), Portuguese (pt), Italian (it), Indonesian (id), Persian (fa), Ukrainian (uk), Kazakh (kk), Turkish (tr), English (en), Russian (ru), Uzbek (uz).

**Candidate != supported or enabled.** The eventual registry must come from model-specific official support evidence.

## Completion evidence
- Source URL and retrieval date for every enabled language
- Language pairs supported/unsupported
- Unit tests and real API test status tracked separately
- CI results and reviewed integration PR
