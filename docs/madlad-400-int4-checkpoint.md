# MADLAD-400 3B INT4 — integration checkpoint

Status: **INT4 export verified. Android integration not started.**
Do not report this module as working in the app until the Android section below is filled in.

## Model identity and provenance

| Item | Value |
| --- | --- |
| Upstream repo | `google/madlad400-3b-mt` |
| Upstream revision | `fa184c675da0b5c9e1c8694fccd4e12e2d422094` |
| License | Apache-2.0, `gated: false` (checked via HF API) |
| Upstream artifacts | `model.safetensors` 11.76 GB fp32, plus `model-q2k/q3k/q4k.gguf`. **No ONNX.** |
| `model.safetensors` SHA-256 | `66ff5f8fcaf92291da486fdfbd4d5233cec90e1359348a56e3172c978b3a76d4` (matches the HF LFS oid; re-verified after re-download) |
| Architecture | `T5ForConditionalGeneration`, d_model 1024, d_kv 128, 32 layers, 16 heads, vocab 256000 |
| Token ids | `decoder_start_token_id` 0, `pad_token_id` 1, `eos_token_id` 2, `tie_word_embeddings` false |

Google ships **no ONNX export**, so the INT4 artifact here is this repository's own export from
the official fp32 weights, not a third-party upload.

### Third-party INT8 export evaluated and rejected as INT4

`CXDuncan/madlad400-3b-mt-optimized-quantized-onnx` @ `8bda8071`:

- `config.json` matches the official config on all 11 architecture keys.
- `spiece.model` is byte-identical to the official one.
- Its `ort_config.json` declares `weights_dtype: QInt8`, so it is **INT8, not INT4**.

Not adopted, because presenting INT8 as INT4 would be a false claim. It was used only to prove
the architecture runs and translates correctly on CPU before committing to the export.

## INT4 artifacts (verified)

Produced with `onnxruntime.quantization.matmul_nbits_quantizer.MatMulNBitsQuantizer`
(block 32, bits 4, weight-only) from the official safetensors.

| File | Bytes | SHA-256 |
| --- | --- | --- |
| `encoder_int4.onnx` | 2,975,135 | `7fb10203458d4e075094fa1f6c8b116bee3a0a56649203c1c0eaa41a4785ffc2` |
| `encoder_int4.onnx.data` | 1,736,710,144 | `f06389af7afb1da4c9a455248cbe0e3bd83d5976427ba7d3bd72c0cc582a05d6` |
| `decoder_int4.onnx` | 5,362,608 | `5e454a3f7521a0796f8ab745d7ff27924aa322588a345ab12b2680b3c89d1733` |
| `decoder_int4.onnx.data` | 2,076,743,680 | `3a328e23e31bd4577f7ca75b67cbf86a803f33c7479a9ba1f6b51933a3d9458e` |

Total 3.6 GB on disk. The encoder sidecar is larger than a pure INT4 encoder because the
`embed_tokens` table (1 GB, 256000 x 1024) is an embedding lookup, not a MatMul, so it stays
unquantized by design.

Compression vs fp32: encoder 5.34 GB → 1.74 GB, decoder 7.47 GB → 2.08 GB.

## Verified inference results

### Fidelity vs fp32 torch (`fidelity.py`)

Same prompts through `model.generate()` (fp32, greedy, `no_repeat_ngram_size=3`) and through the
INT4 graphs:

| Direction | torch fp32 | INT4 ONNX | Verdict |
| --- | --- | --- | --- |
| uz→ru `Salom, qalaysiz?` | `- Привет, как дела?` | `- Привет, как дела?` | identical |
| uz→en `Bugun havo juda issiq.` | `It's very hot today.` | `It's very hot today.` | identical |
| uz→en `Bu kitob juda qiziqarli.` | `This book is very interesting.` | `This book is very interesting.` | identical |
| uz→ru `Men ertaga maktabga boraman.` | `Завтра я в школу.` | `Завтра я пойду в школу.` | INT4 output is more complete |

3 of 4 byte-identical; the fourth differs by producing a fuller sentence, not a worse one.

### Direction sweep (`directions.py`)

| Direction | Input | Output |
| --- | --- | --- |
| uz→ru | `Salom, qalaysiz?` | `- Привет, как дела?` |
| uz→en | `Bugun havo juda issiq.` | `It's very hot today.` |
| uz→en | `Bu kitob juda qiziqarli.` | `This book is very interesting.` |
| uz→ru | `Men ertaga maktabga boraman.` | `Завтра я пойду в школу.` |
| ru→uz | `Privet, kak dela?` | `Қандайсиз?` |
| en→uz | `Hello, how are you?` | `Салом, қандайсиз?` |
| ru→uz | `Segodnya ochen zharko.` | `Бугун жуда иссиқ.` |
| en→uz | `This book is very interesting.` | `Бу китоб жуда қизиқарли.` |

8/8 produced correct target-language text. **Peak RSS 2.21 GB** on 4 threads, ~0.3 s/token.
That is 2.6x below the 5.8 GB the INT8 three-session path needed, and is what makes the phone
target realistic.

### Two bugs found and fixed while getting here

1. **Per-head dim.** `d_kv` is 128, not `d_model / num_heads` (which is 64). Using 64 produced
   `Sizes of tensors must match ... Expected size 64 but got size 128` during with-past export.
2. **Prompt convention.** The `<2xx>` target tag prefixes the **source** text
   (`<2ru> Salom, qalaysiz?`), and the decoder starts from `decoder_start_token_id` alone.
   Seeding the decoder with the tag instead dropped the first output token.

### Other export notes

- `T5ForConditionalGeneration(...)` rejects `encoder_hidden_states`; export must go through
  `model.decoder` plus `model.lm_head`.
- v5 transformers needs `EncoderDecoderCache(DynamicCache(), DynamicCache())`; raw tuples raise
  `'tuple' object has no attribute 'get_query_offset'`.
- The with-past export collapsed to 97 outputs instead of 129 because T5 passes cross-attention
  KV straight through and torch aliases those to input names. Correctness was not affected, but
  that graph was not carried forward: the no-past decoder is sufficient and simpler.

## Known operational hazards

- **`/tmp` does not survive a sandbox restart.** An OOM during decoder export restarted the
  container and deleted an 11.76 GB download and a finished INT4 encoder. All scratch work now
  lives under `/workspace/madlad-work`.
- No swap is configured; a >15 GB peak kills the container instead of degrading.
- Peak RAM by stage: encoder export 5.31 GB, decoder export 9.98 GB, with-past export 11.30 GB.

## External blockers (measured, not assumed)

```
GET /api/offline-models/madlad-400-3b-int4/download-ticket -> HTTP 404 {"detail":"Unknown offline model"}
GET /api/offline-models/nllb-200-600m-int8/download-ticket  -> HTTP 200   (control, works)
```

- `madlad-400-3b-int4` is not registered in the `translator-api/offline_models.py` allowlist.
- No `gsutil`/`gcloud` binary and no Google credentials exist in this environment, so the 3.6 GB
  artifact cannot be uploaded from here.

## Android integration — done, verified off-device

Added in commit `11dbb0b` on `integrate/offline-engines-20261118`:

| Piece | File | State |
| --- | --- | --- |
| Unigram tokenizer + decode driver | `MadladEngine.kt` | verified, 16/16 id checks |
| Encoder/decoder sessions | `avnivo_madlad_jni.cpp` | compiles against real ORT header |
| Engine kind + spec + dispatch | `OfflineModelSpecs.kt`, `OfflineModuleManager.kt` | wired |

Reuses the existing ORT `dlopen` resolution, so no second `libonnxruntime.so` is packaged.

### Bugs found by compiling and running, not by reading

1. `decode` used `replace(METASPACE, " ")` — a `Char`/`String` mismatch that does not compile.
2. `INFINITY` used without `<cmath>`.
3. `supportsLanguage("")` returned **true**: the vocabulary contains the bare `<2>` tag, so a
   membership-only check accepts an empty code and builds a prompt with no language. Now guarded
   by a tag-shape regex.

### What the verification actually covered

- The real Kotlin tokenizer against the real 256k-entry `tokenizer.json`: 16/16, including six
  sentences pinned from the reference `tokenizers` library, decode round-trips, and tag lookup.
- Prompt convention: `<2xx>` prefixes the **source** text, appears exactly once, and the decoder
  starts from `decoder_start_token_id` alone.
- The no-repeat 3-gram guard, as a standalone C++ unit test: 4/4, including the short-sequence
  and no-repeat cases.

### Why the module is still switched off

The 3.6 GB artifact has not been uploaded, and `madlad-400-3b-int4` is not in the ticket
allowlist, so a download would 404. The spec therefore ships an **empty file list** and a
`blocker` that says so. `runnable` now also requires a non-empty file list, which means a spec
with nothing to verify cannot start a download at all.

Turning the module on is data, not code: upload the four artifacts, add the manifest entry with
real byte sizes and SHA-256, and the existing downloader and engine path take over.

## Remaining work

1. Upload the four INT4 artifacts to the model bucket (needs credentials this sandbox lacks).
2. Register `madlad-400-3b-int4` in `translator-api` `offline_models.py` and add its manifest.
3. Point the existing `offline-translate-large` card at the installer **after** step 1, so the
   button cannot 404. The card is deliberately untouched until then.
4. On-device inference run on the emulator via `avnivo-integrated-android.yml`.
