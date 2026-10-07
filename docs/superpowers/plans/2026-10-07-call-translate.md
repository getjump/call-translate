# Call Translate Implementation Plan

> For agentic workers: Use superpowers:executing-plans to implement the approved concept in this session. Steps use checkbox syntax.

**Goal:** An installable offline Android call-caption app with independently selectable models, automatic/manual input language, configurable output language, and explicit speaker-side labels.

**Architecture:** Shell capture runs in Shizuku UserService and streams separate downlink/uplink PCM through file descriptors. The application owns local ASR/translation, bounded audio queues, settings, model installation and captions. CPU is the reference backend; usable acceleration requires a real device check.

**Tech Stack:** Android API 36, Java 17, Gradle 8.13, AGP 8.13.2, Shizuku API, whisper.cpp JNI, ONNX Runtime, SentencePiece JNI.

**Spec:** <local-artifact-directory> plus user steering: automatic/manual source language with live switching, output language in settings, owner/remote speaker identification where capture permits.

## Global Constraints

- All inference and captions run locally after model installation.
- Languages are supported only where the active model supports them.
- Model/backend changes take effect between sessions; language changes are versioned inside a session.
- No audio injection or outgoing call placement without a specific controlled test.
- No commits, push, publishing, or unrelated changes.
- No added code comments. ASCII punctuation in authored prose and code.
- No false GPU/NPU availability or speaker identity claims.

## Review Focus

- Ending or replacing a call must reject results from the old session and close audio descriptors.
- A failed/canceled model download must preserve the last complete installed model.
- Source-language changes must affect subsequent work and reject stale hypotheses.
- Separate uplink/downlink labels must degrade explicitly if only a mixed/single stream is available.
- Background capture, overlay denial and Shizuku loss must remain recoverable without disrupting the call.

## Task 1: Model packages and stream contracts

Files: core/ModelFile.java, core/ModelInstaller.java, core/AudioWindow.java, core/SessionGate.java, core/LanguageCatalog.java; tests in app/src/test/java.

Interfaces:
```java
File install(ModelFile spec, InputStream source, BooleanSupplier canceled) throws IOException;
boolean accepts(long session, long languageRevision);
List<float[]> append(byte[] pcm16le, int length);
```

- [ ] Write and run failing tests for checksum mismatch preserving an existing model, path escape rejection, cancellation preserving existing state, PCM byte boundaries, queue/window sizes, old session and language revision rejection.
- [ ] Implement the contracts and run all unit tests.

## Task 2: Privileged capture and lifecycle

Files: ICaptureService.aidl, capture/CaptureUserService.java, capture/ShellContext.java, capture/ShizukuConnection.java, CallService.java, AndroidManifest.xml.

Interfaces:
```java
ParcelFileDescriptor start(int audioSource);
void stop();
String diagnostics();
```

- [ ] Add device tests that require shell UID, enumerate call capability, open and stop a downlink pipe, and report unavailable audio explicitly.
- [ ] Implement attribution-compatible capture, bounded pipe ownership, stop/destroy paths, foreground session coordination and optional uplink capture.
- [ ] Assemble and install APK. Run the diagnostic harness on the connected OnePlus; a real call is a separate user-started test.

## Task 3: Local engines and model selection

Files: ml/NativeEngine.java, app/src/main/cpp/engine.cpp, app/src/main/cpp/CMakeLists.txt, ml/NllbEngine.java, ml/InferenceWorker.java, app/src/main/assets/catalog.json.

Interfaces:
```java
AsrResult transcribe(float[] audio16k, String sourceLanguage);
String translate(String text, String sourceLanguage, String targetLanguage);
```

- [ ] Add a device test using the saved Serbian IVR fixture and a numeric/negation translation fixture. The test initially reports unavailable native engines/models.
- [ ] Implement multilingual Whisper auto/manual detection and NLLB cached decoding with correct tokenizer mapping and resource closure.
- [ ] Install/download complete verified packages, run both engines on OnePlus and record latency and peak memory.
- [ ] Enable Vulkan only after actual inference succeeds. Record NPU preparation and runtime limits instead of exposing a working toggle without evidence.

## Task 4: User flow and integration

Files: MainActivity.java, CaptionOverlay.java, SettingsStore.java, res/values/themes.xml, README.md, docs/progress.md.

UI: A transcript-first layout with source text subordinate to large translated text; clear speaker-side labels; setup/models/settings separate from the call. Palette: background #F1F5F9, text #172554, secondary #475569, accent #2563EB, listening #0F766E. Native sans-serif for readable phone text. No decorative animation.

- [ ] Connect setup, model install/selection, output-language setting, live input-language selector, start/pause/stop and overlay permission fallback.
- [ ] Add automatic SIM-state observation for user-armed mode and explicit status for missing Shizuku/models or lag.
- [ ] Run unit tests, lint, assemble, device instrumentation and saved-audio end-to-end translation.
- [ ] Obtain a fresh code review, fix important findings, repeat affected checks, copy the complete project to <local-project-checkout> and report verified/unverified behavior.
