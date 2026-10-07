# Whisper turbo QNN/HTP: OnePlus device verification

Date: 2026-10-07. Device: OnePlus 15, CPH2747, SM8850, Android 16, OxygenOS CPH2747_16.0.10.500(EX01). SoC sysfs: `660`, `CANOE`.

Current integration status: the main app now includes NPU and ElevenLabs; see [integrated verification](integration-device-verification.md). The measurements and probe-only status below describe the earlier isolated experiment.

## Result and integration status

The official Qualcomm Whisper large-v3-turbo w8a16 package runs on this phone through QNN/HTP. Three runs transcribed the same 10-second Serbian fixture in about 1 second each. Profiling confirms HTP execution with ORT CPU fallback disabled.

This is an isolated diagnostic APK, package `dev.getjump.calltranslate.npuprobe`. QNN is not yet integrated into the Call Translate model picker, capture service or streaming worker. The production APK and settings were not changed. The ordinary app UID accesses FastRPC through the declared `libcdsprpc.so` native library; inference needs neither Shizuku nor root. SIM capture retains its separate Shizuku requirements.

## Measured latency

| Stage | Three runs, ms | Median, ms |
| --- | --- | --- |
| Turbo ASR, complete diagnostic path | 1007.10, 994.90, 994.88 | 994.90 |
| CPU mel frontend | 271.16, 280.74, 282.36 | 280.74 |
| HTP encoder | 217.65, 201.09, 199.39 | 201.09 |
| HTP autoregressive decoder | 457.88, 453.00, 453.49 | 453.49 |
| Existing NLLB 600M / CPU, complete corresponding text | 752.20, 690.85, 697.00 | 697.00 |

Initial QNN session load: 2285.31ms. NLLB session load in a separate production-app instrumentation process: 3547.70ms. Neither measurement is a reboot-cold startup measurement; file caches may be warm. Model download, integrity verification, audio accumulation, call capture, UI delivery and translation are excluded from ASR timings. ASR total includes sampled PSS collection; direct NLLB timings stop before recording results.

ASR RTF is 0.0995. Compared with the earlier same-fixture turbo Q5 medians, this is about 17.8 times faster than Vulkan (17.73s) and 34.6 times faster than CPU (34.39s). These paths use different quantization, graph exports and runtimes; this is a comparison of usable configurations, not a controlled comparison of identical numerical operations. Thermal state and concurrent workloads were not controlled.

NLLB runs on ORT CPU with four intra-op threads. The measurement uses the same Serbian Latin-to-Cyrillic conversion and `srp_Cyrl` -> `rus_Cyrl` tokens as the application. The three translated texts are identical and preserve keypad number 1. NLLB is substantially faster than the earlier CPU/GPU turbo ASR, but adds about 0.7s after the new NPU ASR on this phrase. Translation latency depends on text length.

## Text and quality boundary

All three NPU runs output:

```text
Za informacije o mobilnoj telefoni i bežičnom kućnom internetu koji koristi SIM karticu, pritisni taster 1.
```

NLLB output:

```text
Для получения информации о мобильных телефонах и беспроводном домашнем Интернете, использующем SIM-карту, нажмите клавишу 1.
```

The ASR text matches the earlier full-context GGML turbo CPU/Vulkan output on this fixture, including `telefoni` where multilingual small produced `telefoniji`. This is one public IVR fixture, not a WER evaluation or evidence of quality on the last bad live call. The NPU model uses its full fixed 30-second mel input with padding. It does not resolve the production worker's independent 4-second cuts or its aggressively shortened Whisper context.

## Execution proof

The session loads `libQnnHtp.so`, selects `high_performance` and sets `session.disable_cpu_ep_fallback=1`. In a separate profiled run, every ORT kernel event has provider `QNNExecutionProvider`: one encoder event and 41 decoder events, with no CPU kernel events. QNN's backend CSV additionally records hardware accelerator execution and eight HVX threads. Encoder accelerator execution is 196048us, versus 216.17ms for the complete profiled encoder invocation. CPU mel extraction and host decode orchestration remain outside those model kernels. [QNN execution provider documentation](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html)

The profiled run passed and returned the same text. Its total of 1139.11ms is excluded from the unprofiled timing table. Process PSS sampled after the unprofiled runs was 78368 / 80167 / 81118KiB. These values do not account for all accelerator-owned buffers or establish total/peak model RAM; model files occupy about 983MB.

## Exact package and runtime

- Model: [Qualcomm Whisper-Large-V3-Turbo-Quantized](https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo-Quantized), w8a16, precompiled QNN ONNX release v0.64.0, Snapdragon 8 Elite Gen 5 For Galaxy asset, HTP81. The package worked on this OnePlus despite the asset's Galaxy label.
- Actual release manifest and package metadata list QAIRT `2.50.0.260828221209` and export ONNX Runtime `1.30.0`. This is newer than the model-card version previously recorded in the research note.
- Diagnostic host runtime: `onnxruntime-android-qnn:1.29.0`, QNN runtime `2.50.0`, JNI ORT C API. Actual execution with this combination is verified; the production app still uses standard ORT `1.30.0` for NLLB.
- Model ZIP: 824020794 bytes, SHA256 `12c4469b443b06ac6ef36b4268f7822cda798c03abadcda0c93f7b9ddbb24f16`. Local and phone hashes of both ONNX wrappers, both binary contexts and metadata match.
- Native/frontend reference: [whisper-htp-android](https://github.com/thedevguy/whisper-htp-android), commit `b33eae5c9985db06a4fb520272c321dea6849907`, MIT license preserved. The probe adapts its quantization constants, variant, Serbian prompt, HTP generation, fallback policy and profiling to this exact turbo package.

## Pipeline implication and remaining work

The current `InferenceWorker` executes ASR, publishes the original caption, then translates synchronously before taking the next audio window. Separate bounded ASR and translation queues would let NPU recognition of the next phrase overlap CPU translation of the preceding phrase. Translation should update the same caption ID. Session/language revision checks must reject stale results on stop, language change and replacement; both engines need bounded cancellation and teardown.

With the isolated stage medians, serial compute is roughly 1.69s per phrase. Ideal overlapping stage throughput is limited by the slower stage, roughly 0.995s here. This is a scheduling estimate, not a measured concurrent pipeline. First-phrase latency still includes ASR plus translation. Partial ASR text can change; translating completed phrases first avoids repeated speculative translations. Shorter bounded phrase windows with sufficient context need their own quality/latency validation.

Before a production NPU backend: preserve NLLB's working runtime, integrate downloads and model validation, implement source-language/Auto behavior and cancellation, replace unsafe context cutting, and measure simultaneous ASR/MT plus a sustained representative call. No sustained thermal/battery test, multilingual auto-detection, live capture with NPU or full application cold-start test was performed here.

## Reproduction and saved evidence

Fixture: `app/src/androidTest/assets/test-serbian.pcm`, 10 seconds mono PCM16 / 16kHz, SHA256 `8b3e67d07cd74fca3046e3710c3c636eb7916d2f2d6289314f1e2df4a03e8f21`.

```sh
adb shell am instrument -w -r -e iterations 3 -e profile false \
  dev.getjump.calltranslate.npuprobe/.ProbeRunner

adb shell am instrument -w -r -e iterations 1 -e profile true \
  dev.getjump.calltranslate.npuprobe/.ProbeRunner

adb shell am instrument -w -r \
  -e class dev.getjump.calltranslate.TranslationLatencyDeviceTest \
  dev.getjump.calltranslate.test/androidx.test.runner.AndroidJUnitRunner
```

Raw results, profiles, model/runtime manifests, instrument logs, diagnostic source archive and APK are stored in `<local-artifact-directory>`. Build and lint passed for the probe and updated production diagnostic tests; the two NPU runs and one NLLB test passed. No outgoing call, cloud inference, developer-setting change, commit or publication was performed.
