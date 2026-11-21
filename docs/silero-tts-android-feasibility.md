# Silero TTS: license gate and Android runtime findings

Scope: converting the validated Silero Uzbek TTS into a shippable Android offline module.
Status: **MIT licence verified (§8). Runtime integrated and verified synthesising on an emulator
(§9.4).** The model is not in the download bucket and the deployed API does not list it, so the
download path cannot complete yet. See §9 for the exact blockers.

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
3. **On-device inference on physical hardware unverified.** Synthesis is now verified on an x86_64
   API 34 emulator (§9.4). No arm64 device or physical phone was available, so the arm64 numbers
   and the 466 MB RSS figure on real hardware remain unmeasured.

## 7. What is deliberately not done

- `navoiy-tts` and `mms-tts` stay `UNAVAILABLE`. They are separate, much larger models that were
  never part of this route; registering them would advertise capability the app does not have.
- No existing file was modified. The tokenizer, its test, the Silero spec and the export/publish
  workflow are new files only. Against the protected original commit `05429fee` the Android sources
  differ by 16,961 insertions and **0 deletions**, so every change is additive and the original HTML
  interface, its assets and its online paths are untouched.

## 8. License verification (recorded 2026-11-21)

The MIT claim for `v5_cis_base.pt` + `uzb_saida` was re-checked against upstream sources rather
than against the earlier notes. Every claim below has a command that reproduces it.

Upstream repository pinned for this check:
`snakers4/silero-models` @ `e382298387332f2bf461fe6eadfde99104611b0c` (2026-10-15).

### 8.1 The licence text is genuine MIT

```console
$ git clone --depth 1 https://github.com/snakers4/silero-models
$ head -1 silero-models/LICENSE_CIS
MIT License
$ head -1 silero-models/LICENSE
Attribution-NonCommercial-ShareAlike 4.0 International
```

The repository ships two licences: the general `LICENSE` is CC BY-NC-SA 4.0, and `LICENSE_CIS` is
MIT. Which one applies is decided per model family in the README.

### 8.2 The README assigns `v5_cis_base` to MIT, not to the NC licence

```console
$ grep -n "published under" silero-models/README.md
143:- All of the below models are published under `MIT` [licence](.../LICENSE_CIS);
```

Line 143 sits inside the `#### V5 CIS Base Models` section (heading on line 138), which covers
exactly the `v5_cis_base` and `v5_cis_base_nostress` families.

### 8.3 `uzb_saida` is listed for `v5_cis_base` in that MIT table

```console
$ grep -n "uzb_saida" silero-models/README.md
166:| `v5_cis_base`, `v5_cis_base_nostress` | `uzb_saida` | `uzb` (Uzbek) | ... |
```

So the MIT grant covers both the weights and the specific Uzbek voice this module uses.

### 8.4 The speaker really is inside the downloaded artifact, at index 46

The speaker list is not taken on trust from the README; it is read out of the published weights.
`v5_cis_base.pt` is a `torch.package` zip whose model pickle can be deserialised without torch:

```console
$ python3 - <<'PY'
import zipfile, pickle, io
class Stub:
    def __init__(self,*a,**k): pass
    def __setstate__(self,st): self._state=st
class U(pickle.Unpickler):
    def find_class(self, mod, name): return type(name,(Stub,),{})
    def persistent_load(self, pid): return Stub()
z = zipfile.ZipFile('v5_cis_base.pt')
st = U(io.BytesIO(z.read('kod_ii_orig_sp_restrict2/tts_models/model'))).load()._state
print(len(st['speakers']), [(i,n) for i,n in enumerate(st['speakers']) if 'uzb' in n])
print(st['speaker_to_package'][46])
PY
60 [(46, 'uzb_saida')]
0
```

60 speakers total, exactly one Uzbek speaker, `uzb_saida` at index **46**, resolved to package `0`.

This corrects an earlier ambiguity: scanning the pickle for "integer next to a name" produced both
45 and 46 depending on the heuristic. Only real deserialisation is authoritative, and it says 46,
which is the value the shipped spec and export script use.

### 8.5 Conclusion

`v5_cis_base.pt` (SHA-256 `ba41b18f…`) and the `uzb_saida` voice are MIT-licensed by the upstream
project, and the voice is present in the published artifact at the index this code uses.
Redistribution of the derived TorchScript artifact is therefore permitted, with the MIT notice
carried in the module's licence field and this document.

Re-checked independently on 2026-11-21 against a fresh clone at the same commit
`e382298387332f2bf461fe6eadfde99104611b0c`, so the conclusion does not rest on the earlier notes:

```console
$ head -1 LICENSE_CIS
MIT License
$ head -1 LICENSE
Attribution-NonCommercial-ShareAlike 4.0 International
$ grep -n "published under" README.md | head -1
143:- All of the below models are published under `MIT` [licence](.../LICENSE_CIS);
$ sed -n '130,145p' README.md | grep -E "^#|published under"
#### V5 CIS Base Models
- All of the below models are published under `MIT` [licence](...);
$ grep -c "uzb_saida" README.md
1
```

The MIT line is inside the `#### V5 CIS Base Models` section, and the single `uzb_saida` row is in
that section's table on line 166. Line 420 of the same README restates it: the base CIS TTS models
are under MIT while everything else is CC-NC-BY.

The non-MIT `v3_uz` / `v4_uz` voices remain unusable and are not referenced by any code.

## 9. Hosting blockers (observed 2026-11-21, run 44911367132)

The code is integrated and compiles, and the JVM suite passes, but the on-device test cannot reach
READY. The emulator run records the exact state:

```text
Error in synthesizesUzbekSpeechOffline(...):
org.junit.ComparisonFailure: module must reach ready only after download + verify + init + smoke test:
{"id":"silero-tts","state":"error","percent":0,"active":false} expected:<[ready]> but was:<[error]>
```

The state is `error`, not a runtime or inference failure: the download never produced a file. Two
independent external gaps cause it.

### 9.1 The deployed API does not know the model id

```console
$ B=https://avnivo-translation-test-792624108019.europe-west1.run.app
$ for m in uzbek-stt whisper-small-q8 nllb-200-600m-int8 navoiy-tts mms-tts silero-tts; do
    printf '%-24s %s\n' "$m" "$(curl -s -o /dev/null -w '%{http_code}' $B/api/offline-models/$m/download-ticket)"; done
uzbek-stt                200
whisper-small-q8         200
nllb-200-600m-int8       200
navoiy-tts               200
mms-tts                  200
silero-tts               404
$ curl -s $B/api/offline-models/silero-tts/download-ticket
{"detail":"Unknown offline model"}
```

The manifest `translator-api/verified_models/silero-tts.json` and the `ALLOWED_PREFIXES` entry are
on this branch, but the running Cloud Run revision was built before them. A redeploy is required.

### 9.2 The artifact is not in the bucket

Anonymous `curl` returns 403 for every object in this bucket, including ones that demonstrably
exist, so 403 alone proves nothing. The authoritative check is the read-only audit of the bucket
itself (run 44913265881, which lists every object under the four model prefixes and hashes it):

```console
multilingual-stt/ objects: 2 bytes: 838505802
multilingual-translation/ objects: 9 bytes: 916816094
stt-uzbek/ objects: 2 bytes: 591649571
tts/ objects: 111 bytes: 5658360536
Verified objects: 124
```

Of the 111 objects under `tts/`, none is a Silero artifact: 88 are `tts/mms/*` and 23 are
`tts/uzbek/navoiy/*` (the 2 GB `emotion_600h_joint.pt` Navoiy model plus its demos and sources).
Grepping the signed manifest for `silero` returns nothing. The ticket endpoint agrees: it signs
URLs only for objects it is told to serve, and it refuses the id outright. So the publish step has
never run successfully, and the 403 seen earlier was not the signal — this manifest is.

### 9.3 Why the publish workflow cannot run yet

`publish-silero-tts-model.yml` is new on this branch. GitHub only exposes `workflow_dispatch` for a
workflow that exists on the default branch:

```console
$ gh workflow run publish-silero-tts-model.yml --ref integrate/offline-engines-20261118
HTTP 404: workflow publish-silero-tts-model.yml not found on the default branch
```

Publishing needs either that file merged to `main`, or the export run by hand with the same pinned
toolchain. Both require a decision this branch cannot make on its own, since merging to `main` is
explicitly out of scope without approval.

### 9.4 What is already proven

- The export is deterministic and reproducible; the workflow re-derives the pinned SHA-256 and
  refuses to publish anything that differs (`ARTIFACT_SHA256` guard).
- `pytorch_android_lite:2.1.0` reads the exported bytecode v8 graph.
- The JVM suite passes, including the new Silero specs, the fail-closed init test and the
  "synthesis refused before READY" test.
- **On-device synthesis works** (run 44914520977, job `tts-seed-smoke`, emulator x86_64 API 34).
  The job exports the graph from the pinned upstream package, refuses to continue unless the export
  reproduces the pinned SHA-256, pushes it into app-private storage with `run-as`, then runs the
  real runtime. Measured from `logcat_seed.txt`:

  ```console
  I AvnivoSileroTts: seed init+synthesis=2512ms samples=102000 seconds=2.125 peak=0.7822921
  OK (1 test)
  ```

  That is 2.125 s of 48 kHz audio from the Uzbek voice with a non-silent peak, reached through
  `reactivateInstalled()` → `initialize()` → `SileroTtsEngine.prepare()` → `smokeTest()`, i.e. the
  same path a real install takes once the file is on disk. Because the module only reaches `ready`
  after the smoke test passes, this also proves the READY gate is not bypassed.

What is still *not* proven is the download path end to end, because the artifact is not in the
bucket and the deployed API does not list the id (§9.1, §9.2). The download test therefore still
fails, and this module stays **IMPLEMENTED — DEVICE TEST PENDING** rather than VERIFIED WORKING.
Seeding proves the runtime; it does not prove the downloader, the ticket, or the checksum gate on a
real transfer, and it must not be reported as if it did.
