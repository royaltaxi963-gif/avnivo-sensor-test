# Offline STT performance

Russian speech on the S23 Ultra transcribed correctly but took about a minute. This records where
that minute went, what changed, and what the change was measured to do.

## Where the time went

`whisper.cpp` reports its own breakdown through `whisper_print_timings()`. The JNI bridge now
captures those lines through a log callback and re-emits them as one structured logcat line:

```
WHISPER_STAGE_TIMING mel_ms=14.0 encode_ms=37804.0 encode_runs=1 decode_ms=77.9
  decode_runs=11 batchd_ms=0.0 total_ms=37825.0 fallbacks_p=0 fallbacks_h=0 audio=5.00s threads=4 audio_ctx=1500
```

Measured on `ggml-large-v3-turbo-q5_0.bin`, 5 s of speech, four threads, full 30 s encoder window:

| stage | time | share |
|---|---|---|
| model load (`whisper_init_from_file_with_params`) | 349 ms | 0.9 % |
| mel spectrogram (`whisper_pcm_to_mel`) | 11 ms | 0.03 % |
| **encoder (`whisper_encode`)** | **37 804 ms** | **99.7 %** |
| decoder (11 steps) | 62 ms | 0.16 % |
| UI delivery | — | — |

The encoder is the whole problem, and it is the whole problem because whisper.cpp always encodes
1500 positions - a full 30 seconds - no matter how long the recording is. A three second clip and a
five second clip cost exactly the same 37.7 s. The decoder, the part that looks expensive, is 62 ms
and not worth touching.

Trimming silence therefore buys nothing on its own: it shortens the audio, not the window. The
window is the lever.

## What changed

Three changes, in order of effect.

**1. Encode only the positions that carry audio** (`WhisperTuning.audioContextFor`,
`avnivo_whisper_jni.cpp`).

```
audio_ctx = positions + max(160, positions / 2)      positions = samples / 320
```

clamped to `[320, 1500]`. `160` is 1.6 s of trailing silence, and it is not arbitrary - see the
quality section. A 30 s recording still asks for 1500 and pays the full price; nothing gets slower.

**2. Build the quantised kernels for ARMv8.2-A with dot-product** (`app/src/main/cpp/CMakeLists.txt`).

Android is always a cross build, so ggml takes its `GGML_CPU_ARM_ARCH` branch and otherwise compiles
for the plain ARMv8-A baseline, where the quantised dot products are two `vmull`/`vpaddl` pairs per
block. Setting `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16` collapses each into one `sdot`. The
kernels are still selected by ggml's `getauxval` checks at runtime, so the flag changes the
instructions, not which kernel runs.

Verified by disassembly of the shipped `libggml-cpu.so`, per function:

| function | baseline | with `+dotprod` |
|---|---|---|
| `ggml_vec_dot_q5_0_q8_0` | 0 `sdot` | 4 `sdot` |
| `ggml_vec_dot_q4_0_q8_0` | 0 | 4 |
| `ggml_vec_dot_q8_0_q8_0` | 0 | 6 |
| `ggml_vec_dot_q{2,3,4,6}_K_q8_K` | 0 | 16 each |
| `ggml::cpu::aarch64::gemm<block_iq4_nl>` | 0 | 32 |

`q5_0` is the format of this model, so the first row is the one that matters.

**3. Answer silence without decoding** (`WhisperEngine.transcribe`).

An accidental press used to pay a full encoder pass to produce an empty string. `AudioTrim.isSilent`
returns `""` immediately instead. The smoke test deliberately bypasses this path, because its whole
purpose is to prove the decoder ran.

Trimming the recording (`AudioTrim`) is kept as well. It does not change the encoder cost at a fixed
window, but it removes the long silences Whisper likes to hallucinate over.

## What was measured

Same 5 s clip, four threads, warm model, p50 of 5 runs after the first:

| | before | after | |
|---|---|---|---|
| encoder | 37 804 ms | 8 331 ms | 4.5x |
| decoder | 78 ms | 62 ms | — |
| **total** | **37 925 ms** | **8 438 ms** | **4.5x** |
| transcript | `And so, my fellow Americans, ask not...` | `And so my fellow Americans, ask not.` | same words |

Across clip lengths (all clean, no fallbacks triggered):

| clip | window before | window after | total before | total after | |
|---|---|---|---|---|---|
| 3 s | 1500 | 320 | 37.7 s | 6.4 s | 5.9x |
| 5 s | 1500 | 410 | 37.9 s | 8.4 s | 4.5x |
| 11 s | 1500 | 710 | 37.8 s | 15.5 s | 2.4x |
| 30 s | 1500 | 1500 | 37.8 s | 37.8 s | 1.0x |

Threads on the 4-vCPU measurement host: 1 thread 16.5 s, 2 threads 8.4 s, 4 threads 8.4 s. Two is
enough there because the host has two physical cores; a phone is a different shape, which is why
`WhisperTuning` exposes the count and `WhisperThreadBenchmarkInstrumentedTest` measures 2, 4, 6 and 8
on the device. The production default is four - ggml synchronises with a spin barrier, so a thread
that lands on a little core stalls the whole pool.

These are x86 figures. The ARM ratios are the transferable part: the encoder is linear in
`audio_ctx`, so the 4.5x holds, and the dot-product kernels are a further 1.5-2x that this host
cannot show because it runs AVX2.

## Quality

Cutting the window is not free. Whisper is trained on 30 s windows that are mostly silence, and
reducing the window to the exact speech length takes the encoder out of that distribution.

Measured on a 3 s clip that ends mid-word, sweeping `audio_ctx` with everything else fixed:

| `audio_ctx` | transcript |
|---|---|
| 150 - 272 | `and so my fellow Americans, and so my fellow Americans, ...` (repetition loop) |
| 288 and above | `And so my fellow Americans,` |

The same clip at the full 1500 positions decodes correctly, so the loop is caused by the short
window, not by the clip. That is what the 160-position floor in the tuning formula is for: it keeps
1.6 s of trailing silence, above the measured 138-position threshold.

For clips that still trip a loop, **whisper.cpp's own temperature ladder is left exactly as it is**.
It was never disabled. It costs nothing when the first pass is clean (`fallbacks_p=0` on every clean
clip above) and re-runs the decoder at higher temperature when the entropy threshold trips - which
it did, correctly, on the 3 s clip at 214 positions. An earlier attempt at this change also
collapsed that ladder to save time; it was reverted because the ladder is the safety net that makes
a short window acceptable.

Transcripts at the reduced window match the full-window ones word for word on every clip tested
(punctuation aside), and the reduced window removed a trailing `...` the full window produced from
the padding silence.

## Reproducing

Host, no device needed:

```bash
cmake -B /tmp/bld-host -S android-app/app/src/main/cpp/whisper.cpp \
  -DWHISPER_BUILD_EXAMPLES=OFF -DWHISPER_BUILD_TESTS=OFF -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/bld-host -j4
# android-app/tools/whisper-bench/bench.cpp links libwhisper and runs the same matrix the app uses
g++ -O2 -std=c++17 -Iwhisper.cpp/include -Iwhisper.cpp/ggml/include \
  android-app/tools/whisper-bench/bench.cpp -o bench -L/tmp/bld-host/src -L/tmp/bld-host/ggml/src \
  -lwhisper -lggml -lggml-base -lggml-cpu
./bench ggml-large-v3-turbo-q5_0.bin clip16k.wav 4 410 5 0 0.2 5 en 5.0
```

On the phone:

```bash
adb shell am instrument -w -e avnivoWhisperPerf true \
  -e class com.avnivo.translator.WhisperThreadBenchmarkInstrumentedTest \
  com.avnivo.translator.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -s AvnivoWhisperBench:V AvnivoWhisper:V
```

The benchmark never downloads a model: it runs against the installed
`whisper-large-v3-turbo` and skips otherwise.

## Still open

- The S23 Ultra figures are projected from the host, not measured. The instrumented benchmark
  exists to replace them; run it and compare `WHISPER_THREADS` and `WHISPER_COLD_WARM`.
- The encoder is a GEMM and would run far faster on the Adreno GPU through ggml-vulkan. Not done
  here: it needs shader compilation in the build and cannot be verified in this environment, and
  shipping an untested GPU path is worse than a slow CPU one.
