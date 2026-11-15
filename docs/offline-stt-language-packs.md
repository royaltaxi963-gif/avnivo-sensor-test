# AVNIVO offline STT language packs — implementation contract

Status: Phase 1, specification only. No model binaries uploaded; no APK downloader implemented. Do not advertise any language as installed until a device test succeeds.

## Objective
One Android offline STT manager with separately downloadable, language-specific packs. Prioritize real-world transcription accuracy over package size. Preserve the existing working Uzbek model and online Google Chirp 2 path. Do not restore Groq.

## Required languages
| language | BCP-47 | package ID | initial status |
|---|---|---|---|
| Uzbek | uz-UZ | stt-uz | existing model; verify APK integration |
| Russian | ru-RU | stt-ru | candidate evaluation |
| English | en-US | stt-en | candidate evaluation |
| Hindi | hi-IN | stt-hi | candidate evaluation |
| Bengali (Bangladesh) | bn-BD | stt-bn | candidate evaluation |
| Turkish | tr-TR | stt-tr | candidate evaluation |
| Turkmen | tk-TM | stt-tk | candidate evaluation |

## Catalog format (proposed, not live)
Serve a versioned HTTPS JSON catalog, e.g. GET /api/offline-stt/catalog. Every published entry MUST have:
- id, language_tag, display_name, model_family, model_version, runtime, runtime_version, bytes, sha256, download_url, license_id, license_url, benchmark_url, status.
- download_url is a direct HTTPS file/object-storage URL for the exact package, not a Telegram channel or generic page.
- status=available ONLY after compatible Android-device execution and integrity verification; otherwise candidate/unavailable.
- Never populate fabricated file sizes, checksums, download links or benchmark scores.

## Android download state machine
not_installed -> checking_storage -> downloading -> verifying_sha256 -> extracting_to_staging -> activating -> installed.
On failure, preserve the previous working pack; delete staging artifacts. Show download progress, errors and retry; allow cancellation, resume when supported by server, and pack deletion. Download only on user action. Check disk space, package authenticity/integrity, compatible CPU/ABI, and license before activation. Store packs in app-private storage, never execute untrusted code from a pack. Use HTTPS and trusted hosts. Avoid hard-coded public write credentials.

## Language routing
The clicked language ID resolves to exactly one catalog entry and exact versioned artifact. Validate requested language_tag against the manifest inside the package before activation. Never silently fall back to another language pack. Load only the selected pack in memory; unload prior pack on language switch.

## Model evaluation gate
For each language collect at least two candidates where available; evaluate on held-out natural speech in the target dialect/accent, noise and spontaneous speech. Record WER (or CER for appropriate scripts), real-time factor, peak RAM, model download bytes, license, and reproducible sample counts. Do not compare WER values from different datasets as if directly comparable. Turkmen and Uzbek require especially careful validation. Record model provenance and redistribution rights. Do not label any candidate 'best' without same-test-set evidence.

## Optional universal model
Separate opt-in 1600+ language pack; do not bundle by default. Show exact artifact size and measured RAM requirement for the chosen exported mobile format, with storage warning; no enabled Download button until verified compatible with Android and hosting/redistribution permitted.

## Delivery phases
1. Inventory current Android application and working Uzbek STT model, runtime, and distribution rights.
2. Benchmark and select per-language candidates; create reviewed catalog with real artifacts and checksums.
3. Host versioned model files on object storage; backend serves metadata, not huge binary proxy.
4. Implement Android in-app download, verification, lifecycle and settings UI.
5. Run device offline acceptance tests for all seven languages, airplane mode, interruptions, low storage, corrupt package, wrong-language package, rollback.

Do not deploy to production or spend paid API/cloud resources without explicit authorization.
