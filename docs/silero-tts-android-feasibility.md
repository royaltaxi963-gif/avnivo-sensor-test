# Silero TTS: license gate and Android runtime findings

Scope: converting the validated Silero Uzbek TTS into a shippable Android offline module.
Status: **BLOCKED — model hosting and on-device runtime not yet verified.** Nothing here is
claimed as working on a device.

## 1. The validated configuration cannot be shipped

The validated setup was `v3_uz.pt` with speaker `dilnavoz` (48 kHz, PyTorch CPU, Ubuntu proot).
That model is **not** MIT-licensed and cannot be distributed with this application.

Evidence from `snakers4/silero-models`:

- `LICENSE` is **CC BY-NC-SA 4.0** — non-commercial, share-alike.
- The README licence section carves out only the `base` CIS models as **MIT**
  (`LICENSE_CIS`).
- `models.yml` does not list `v3_uz`/`v4_uz` in the MIT set, and `v4_uz` (`dilnavoz`) appears only
  in the non-MIT V4 table. So the exact voice that was validated is unusable for a commercial app.

Piper was checked as an alternative and has **no Uzbek voice** (183 voices across 53 languages), so
it is not a path either.

## 2. The MIT-licensed alternative

`v5_cis_base.pt` — MIT (`LICENSE_CIS`) — contains the Uzbek speaker **`uzb_saida`** (id 46).
It is the only legally usable Uzbek TTS option in Silero.

Measured on the real model (CPU, x86_64 reference):

| Metric | Value |
| --- | --- |
| SHA-256 | `ba41b18f6a707ad93605a162998865e7c087153d2e010a26dd02229dab0e672a` |
| Size | 91,680,514 bytes |
| Real Uzbek synthesis | 3.45 s audio in 0.53 s wall, **RTF 0.154** |
| Peak RSS during synthesis | 466 MB |

## 3. ONNX export is not available

Five export attempts all fail identically inside PyTorch:

```
RuntimeError input_values.size() == param_count_list.size()
INTERNAL ASSERT FAILED at "torch/csrc/jit/ir/graph_utils.cpp":56
(input_values:310 vs param_count_list:301)
```

Tried: dynamo exporter at opsets 17/16/14, the legacy exporter, `torch.jit.freeze`, a thin
wrapper, and plain `torch.trace`. Unpacking the quantized model first does not help, and
`TS2EPConverter` does not exist in this torch build. There is no working ONNX path, so the
existing sherpa-onnx VITS route cannot host this model.

## 4. The TorchScript route works

`_save_for_lite_interpreter` produces a PyTorch Mobile artifact, and the traced module is
numerically identical to the original:

| Text | Tokens | Original | Traced |
| --- | --- | --- | --- |
| `salom` | 7 | 32400 | 32400, identical |
| `rahmat` | 8 | 37200 | 37200, identical |
| `O'zbekiston` | 13 | 49200 | 49200, identical |
| `yaxshi` | 8 | 37200 | 37200, identical |

Bytecode version is the deciding detail. Building with torch 2.15 emits **bytecode 10**, but
`org.pytorch:pytorch_android_lite` is published only at **2.1.0**, which reads **bytecode 8**.
Exporting from a Python 3.11 + torch 2.1.0 environment emits version 8, and that artifact reloads
and runs (`torch.Size([1, 32400])`, peak 0.625).

Artifact: `silero_v21.ptl`, 91,988,758 bytes, bytecode v8.

## 5. Text frontend, reimplemented in pure JVM Kotlin

The scripted network takes token ids only; all text handling lives in Python inside the package
(627 lines, including an SSML parser). For plain text the path is short and reproducible, and both
steps were verified against the real model:

- `convert_to_orig` performs a **single-character** lookup. The digraph keys (`sh`, `ch`, `o'`) in
  its `ext_alph` are unreachable in its own code, so they are never applied: `shahar` becomes
  `сҳаҳар`, not `шаҳар`. Reproducing this exactly is what keeps the audio identical.
- Characters without an id are **dropped**, not substituted: `choy` loses its `c` and still speaks.
  A `ValueError` is raised only when nothing speakable remains.

`SileroTtsTokenizer` implements this with no new dependency. `SileroTtsTokenizerTest` pins every
value against ids recorded from the real model; 8/8 pass. The ids reproduce the model's own audio
for `choy` (0.5375 s on both paths).

## 6. Remaining blockers

1. **Model hosting.** The downloader only accepts allowlisted HTTPS prefixes
   (`storage.googleapis.com`, `avnivo-offline-models-792624108019`). No GCS credentials are
   available in this environment, so the 92 MB artifact cannot be published, and without a hosted,
   checksum-pinned artifact the module cannot move past `NOT_INSTALLED`.
2. **Android runtime size and memory.** `pytorch_android_lite` adds a large native dependency to an
   app that already bundles sherpa-onnx, onnxruntime and whisper.cpp. The 466 MB RSS measured on
   x86_64 is above a safe budget for many devices; the figure on an actual arm64 device is not
   measured. Adding this dependency affects every build and has not been justified against a
   measured on-device number.
3. **On-device inference unverified.** No emulator or physical device was available in this
   environment. The APK-level claims for this module are therefore unverified.

## 7. What is deliberately not done

- No `OfflineModelSpec` entry was added for Silero. `navoiy-tts` and `mms-tts` stay `UNAVAILABLE`
  because there is still no hosted artifact and no verified runtime; registering a module that
  cannot become `READY` would advertise a capability the app does not have.
- No existing file was modified. The tokenizer and its test are new files only.

## 8. Next exact action

Obtain authorization and credentials to publish `silero_v21.ptl` to the model bucket with a pinned
SHA-256, then measure RSS for one synthesis on an arm64 emulator before deciding whether
`pytorch_android_lite` is acceptable in the APK.
