# Call Translate: ASR comparison and GPU/NPU research

Date: 2026-10-07. Device: OnePlus 15, CPH2747, QTI SM8850, Android 16 / OxygenOS CPH2747_16.0.10.500(EX01).

## Decision

GPU acceleration works for Whisper turbo, but the measured speed is still below real time. The initial comparison selected the official Qualcomm turbo w8a16 export through QNN/HTP as the next experiment. The subsequent isolated probe now confirms about 0.995s per 10-second fixture with HTP execution; see [fresh NPU verification](npu-device-verification.md). The sections below preserve the original comparison and research. Meta CTC on CPU is a useful fast comparison candidate, with word errors on this fixture. Neither model becomes the production default from this experiment.

The canceled Serbian fine-tuned small model was not downloaded or added. The small in this report is the existing multilingual Whisper small Q5.

## Measured on the OnePlus

All rows use the same 10-second Serbian IVR fixture, mono 16kHz PCM16. Each row is the median of three complete inference runs after loading the model. Model load, audio accumulation, translation and UI delivery are excluded. Whole-window Whisper uses its full encoder context and manual Serbian input. Meta uses raw-waveform normalization, INT8 ONNX, greedy CTC decoding and six CPU threads. The final Meta timing runs have profiling disabled for every provider.

| Model / backend | Runs, ms | Median | RTF | Largest sampled process PSS |
| --- | --- | --- | --- | --- |
| Whisper small Q5 / CPU | 6065, 4865, 4957 | 4.96s | 0.496 | 399MiB |
| Whisper turbo Q5 / CPU | 27584, 34392, 34390 | 34.39s | 3.439 | 775MiB |
| Whisper turbo Q5 / Vulkan | 21777, 17725, 17722 | 17.73s | 1.773 | 749MiB |
| Meta CTC 300M v2 INT8 / CPU | 1410, 1259, 1330 | 1.33s | 0.133 | 1108MiB |
| Meta CTC / NNAPI | 1408, 1277, 1315 | 1.32s | 0.132 | 1137MiB |
| Meta CTC / WebGPU | 2601, 2018, 2115 | 2.12s | 0.212 | 952MiB |
| Meta CTC / XNNPACK selected | 1372, 1293, 1230 | 1.29s | 0.129 | 1123MiB |

RTF is inference time divided by audio duration; below 1 is faster than real time. It does not mean that captions appear immediately: a whole 10-second window must first accumulate. PSS is sampled after each run, not peak allocation, and these isolated tests do not load NLLB alongside ASR.

Vulkan made turbo about 1.94 times faster in this experiment. Its median remains 17.73 seconds for 10 seconds of speech. Meta's WebGPU path was slower than CPU for this export and runtime. These results do not establish the fastest backend for other models.

This is one fixture, not an accuracy benchmark with independently annotated reference text. The phone was connected to USB and charged. Battery temperature was 32.8C before the comparison and reached 37.4C during turbo CPU testing. Runs were sequential, without controlled temperature, clock settings or a sustained-call thermal test. Small has two recorded sweeps; the table takes the last three whole-window measurements. Turbo CPU's full sweep was intentionally stopped after its three complete whole-window measurements and additional short-window probes. That interrupted suite is not reported as a passing full test.

## Recognition and context findings

Multilingual small, whole 10 seconds, full encoder context:

```text
Za informacije o mobilnoj telefoniji i bežičnom kućnom internetu koji koristi sim karticu, pritisni taster 1.
```

Turbo, whole 10 seconds, CPU and Vulkan:

```text
Za informacije o mobilnoj telefoni i bežičnom kućnom internetu koji koristi SIM karticu, pritisni taster 1.
```

Meta CPU:

```text
за информације о мовилној телефонији и бежичном кућном интернету који користи свим картицу притисни тастир 1
```

Meta is fast, but errors include "мовилној", "свим" and "тастир". GPU/NNAPI output differs slightly at the last word. The speed advantage does not establish a quality win.

Two separate mechanisms can damage the current streaming implementation:

1. Splitting speech into independent 4-second windows loses word and sentence context. Small still made boundary errors with the full encoder context on each isolated 4-second cut. The last isolated 2-second tail produced invented repetitions; Meta produced Arabic text on that tail too.
2. Reducing Whisper encoder context is independently unsafe on this sample. On exactly the same first 8 seconds, small with context 576 produced long repeated phrases. Full context produced a meaningful Serbian sentence in 4935ms; context 1024 produced a meaningful prefix in 3296ms. Turbo with context 640 on the whole 10 seconds produced repeated English fragments, while its full context produced Serbian.

Consequently, the current 4-second, aggressively shortened-context path remains a known quality risk. The bad last live call was not saved, so this experiment does not establish its exact root cause. The production code and APK were not changed by this comparison. Overlapping context, VAD and stable-prefix emission remain separate implementation work.

## Was it actually GPU/NPU?

The packaged ONNX Runtime 1.30.0 reported `[CPU, NNAPI, XNNPACK, WEBGPU]`. QNN is absent. Enabling an EP is not proof that the graph executes there. Separate profiled runs establish placement; the timing table above uses later runs with profiling disabled.

| Selected EP | Kernel events and cumulative duration from traces | Interpretation |
| --- | --- | --- |
| NNAPI | CPU: 12918 events / 7.712s; NNAPI: 732 / 0.211s | Most work returned to ORT CPU |
| WebGPU | GPU: 10098 / 6.884s; CPU: 3624 / 1.684s | Actual mixed GPU/CPU execution |
| XNNPACK | CPU: 7464 / 4.593s; no XNNPACK provider events | No demonstrated XNNPACK execution |

The aggregates cover three whole-window runs plus three short chunks. They count executions, not distinct graph nodes, and are not end-to-end latency measurements. NNAPI used `CPU_DISABLED`; that disables NNAPI's reference CPU device, but it does not disable ONNX Runtime CPU fallback. NNAPI can target multiple accelerator types, so its EP name alone does not establish NPU use. [ONNX Runtime NNAPI documentation](https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html)

## Concrete NPU route

Qualcomm publishes [Whisper-Large-V3-Turbo-Quantized](https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo-Quantized), using w8a16 and a 30-second, 128x3000 input. Its precompiled QNN package for Snapdragon 8 Elite Gen 5 For Galaxy lists QAIRT 2.50 and ONNX Runtime 1.27.1. Published component timings on that chipset are 133.546ms for the encoder and 3.34ms per decoder step. These are vendor measurements, not end-to-end transcription or measurements on this OnePlus. Compatibility with the phone's SM8850 and Serbian accuracy still need validation.

The existing GGML Q5 file cannot simply be selected for NPU. This path needs a separate model package and QNN runtime, with encoder/decoder sessions, fixed-shape KV-cache handling, mel preprocessing, tokenizer and language prompt. QNN HTP runs through `libQnnHtp.so`; dynamic shapes are unsupported. In an isolated validation run, disable ORT CPU fallback and verify HTP profiling so an apparent success cannot silently be CPU execution. Cache or use precompiled context binaries to avoid repeated compilation. [QNN EP documentation](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html)

An existing [Android Whisper HTP prototype](https://github.com/thedevguy/whisper-htp-android) demonstrates an ordinary app with JNI decoding, QNN and Maven runtime dependencies. Its defaults target a different HTP generation and Portuguese; they are not ready-made OnePlus/Serbian settings. Use it as an integration reference after checking its code and model ABI. Its manifest declares the FastRPC vendor library `libcdsprpc.so`; Android requires declarations to expose such native libraries to apps targeting API 31+. [Android native library manifest documentation](https://developer.android.com/guide/topics/manifest/uses-native-library-element)

Read-only Maven Central metadata checks found public artifacts `com.microsoft.onnxruntime:onnxruntime-android-qnn` (latest 1.29.0) and `com.qualcomm.qti:qnn-runtime` (latest 2.51.0). They were not installed. Match the runtime to the selected model package rather than mixing the latest versions blindly. [ORT QNN metadata](https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android-qnn/maven-metadata.xml), [QNN runtime metadata](https://repo.maven.apache.org/maven2/com/qualcomm/qti/qnn-runtime/maven-metadata.xml)

NPU inference runs within a normal app; it does not require Shizuku or root. The current SIM audio acquisition still has its separate Shizuku/OxygenOS requirements. Once packages are downloaded, local inference does not require network access.

The current Meta export has dynamic waveform shapes and dynamic quantization. It is not an established drop-in HTP model. Static export, compatible quantization and supported operations would need their own port. Ready [sherpa QNN examples for SM8850](https://k2-fsa.github.io/sherpa/onnx/qnn/models.html) demonstrate the hardware route, but the listed Chinese/SenseVoice examples do not solve Serbian recognition.

## Recommended implementation boundary

First build an isolated turbo QNN diagnostic on the same fixture: prove HTP execution, compare its Serbian text against the full-context CPU output, measure load separately and repeat with warm sessions. Then measure 10-15 minutes of representative audio for sustained latency and temperature. Only after that should it become an experimental picker backend. Keep CPU as an explicit fallback; report fallback rather than labeling it NPU.

Prioritize ASR acceleration. NLLB previously took about 0.5 seconds for a short numeric fixture, whereas turbo CPU takes tens of seconds here. Translation acceleration can follow if measurements show that it becomes the bottleneck. Changing the ORT AAR must also rerun NLLB and cancellation checks because the current app relies on ORT 1.30.0 after a previous native crash with an older runtime.

## Reproduction and artifacts

Diagnostic class: `app/src/androidTest/java/dev/getjump/calltranslate/ModelComparisonDeviceTest.java`.

Fixture: `app/src/androidTest/assets/test-serbian.pcm`, 320000 bytes, SHA256 `8b3e67d07cd74fca3046e3710c3c636eb7916d2f2d6289314f1e2df4a03e8f21`.

Models are installed into the app-private model directory. Turbo GGML was obtained from the existing pinned catalog. Meta INT8 ONNX and tokens were obtained from the official sherpa release archive. Download/file hashes are preserved in `comparison-model-downloads.json` and `meta-ctc-model-files.json`; remote copies were checked against local hashes. Meta is a diagnostic-only model and was not added to the production picker. [Meta release package](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05.tar.bz2)

```sh
adb shell am instrument -w -r \
  -e class dev.getjump.calltranslate.ModelComparisonDeviceTest#compareWhisperOnIdenticalAudio \
  -e candidate whisper-turbo-q5 -e backend Vulkan -e scope full \
  dev.getjump.calltranslate.test/androidx.test.runner.AndroidJUnitRunner

adb shell am instrument -w -r \
  -e class dev.getjump.calltranslate.ModelComparisonDeviceTest#compareMetaCtcOnIdenticalAudio \
  -e backend WebGPU -e profile false -e scope full -e threads 6 \
  dev.getjump.calltranslate.test/androidx.test.runner.AndroidJUnitRunner
```

Set the Meta backend to CPU, NNAPI or XNNPACK for the other timings. Omit `scope full` and enable profiling for the placement/short-chunk diagnostic. Instrumentation assertions check successful execution, nonblank output and manual Whisper language; they are not an accuracy oracle. The current small, Meta, Vulkan and all four unprofiled Meta tests completed successfully. Core tests, lint and the final diagnostic APK build passed.

Saved under `<local-artifact-directory>`: this report, raw comparison-only JSONL, summarized JSON, model manifests, compressed provider traces, test logs and the analysis script. The raw logs exclude unrelated system logcat. No outgoing call, voice injection, developer-setting toggle, commit or publication was performed by a tool.
