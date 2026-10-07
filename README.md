# Call Translate

**Suspended. Unmaintained. Unlikely to be resumed.**

An Android experiment for live SIM-call transcription and translation, with local Whisper/NLLB models, an experimental Qualcomm NPU backend, ElevenLabs realtime recognition, and an unfinished voice-agent mode.

This is a source snapshot, not a reliable call assistant. There is no support commitment, release roadmap, production-readiness claim or promise to review issues or pull requests. Fork it if you want to continue.

## Why we stopped

We wanted to understand Serbian callers without turning every phone call into a debugging session. Transcription and translation showed useful results, but setup friction, latency, recognition errors and fragile SIM audio routing made the whole experience worse than expected. The voice agent was briefly audible on the owner's phone, then stopped halfway through a phrase. Reliable audio delivery to the remote caller was never established.

### Коротко, по-русски

Сначала пытались подключить OnePlus к Mac как гарнитуру. Потом полезли в Android, Shizuku, OxygenOS, NPU и телефонные аудиомаршруты.

Apple и Bluetooth не дали надежного пути через Mac. Android и OxygenOS добавили ограничения доступа к аудио и хрупкие обходные маршруты. В итоге вместо простого помощника для звонков получился затяжной эксперимент с настройками, моделями и аудиомаршрутами.

Кое-что заработало. Нормального бесшовного продукта не получилось. Автору хватило. Проект suspended и вряд ли будет продолжен.

Исходники здесь. Если хотите продолжить - форкайте. Поддержка и дальнейшая разработка не обещаны.

## What is here

| Component | What was actually established |
| --- | --- |
| SIM audio capture | Shizuku downlink capture was exercised on one OnePlus 15 / OxygenOS 16 / Android 16. This is not portable public Android call-audio access. |
| Local ASR | Multilingual whisper.cpp on CPU/Vulkan. Accuracy and speed vary; short chunks, related-language detection and hallucinations remain problems. |
| NPU ASR | Whisper large-v3-turbo w8a16 through QNN/HTP81 was measured on the target phone. The export/runtime combination is tightly device-specific. |
| Translation | NLLB-200 distilled 600M ONNX on CPU, separate from the ASR queue. Progressive draft translation and later replacement of the same caption are implemented. Quality and end-to-end latency are not guaranteed. |
| Cloud ASR | ElevenLabs Scribe v2 Realtime, API-key validation and model picker. A real test gave better text/partial-caption UX than the local path. It sends call audio to ElevenLabs. |
| Transcript UI | Shared scrollable history, original text before translation, draft updates, app and overlay views. History is in process memory, not a durable call archive. |
| Voice-agent microphone demo | An owned ElevenLabs agent could listen and reply through the speaker. |
| Voice agent in SIM calls | Experimental downlink input, uplink injection attempt and local monitor, private text instructions and owner mute control. A local reply was heard but truncated; reliable remote speech, mute interaction and echo behavior remain unverified. |
| Promotional videos | Two complete React/Remotion source projects under [media](media/README.md). They show product concepts and fictional situations, not validated live-agent capabilities. |

Android 14+ and arm64-v8a are the build targets. Only the above OnePlus was exercised. This is not a validated device matrix.

## Explicit boundaries

- SIM mode requires Shizuku and, on the tested OxygenOS build, additional ADB access through a system-wide developer setting. See [the device setup notes](docs/oxygenos-shizuku.md). This is a major usability limitation.
- Automatic language detection can confuse Serbian and Croatian. Manual input language selection is available and often preferable.
- Local Whisper and NLLB have substantial startup, RAM, battery and thermal costs. NLLB translation is CPU-only. Faster single-fixture timings do not establish smooth long calls.
- The speaking agent is unfinished. Do not treat a fake-route test, an available framework method, or a locally audible fragment as proof that the remote caller hears it.
- Private voice coaching is not implemented. Owner instructions are text only. Agent dialogue shows the original transcript; it does not have the translation mode's full translated UX.
- Telegram/WhatsApp call capture, general system-media subtitles and DRM-player compatibility were not implemented or validated.
- Cloud modes need your own ElevenLabs API key and account permissions. There is no OAuth, subscription broker, backend proxy or automatic cloud fallback.
- Some historical research notes contain dated product/pricing hypotheses. [STATUS.md](STATUS.md) and this README take precedence over old progress notes and promotional concepts.

## Build from source

Requirements: Python 3, JDK 17, Android SDK platform 36, build-tools 35.0.0, NDK 28.2.13676358 and CMake 3.22.1. Gradle 8.13 is provided through the wrapper. Set `ANDROID_HOME` or put `sdk.dir` in a local, ignored `local.properties`.

```sh
python3 scripts/bootstrap-dependencies.py
./gradlew :core:test :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The bootstrap downloads the exact NLLB SentencePiece tokenizer, verifies its SHA-256 and installs it as an APK asset. It does not download full ASR/translation weights; the application model picker handles those separately. Vendored native sources and the applied Whisper patch are pinned in [vendor-lock.json](vendor-lock.json) and [patches](patches/).

QNN binaries are intentionally not committed. If you want to reproduce the experimental HTP81 configuration, first review the upstream runtime terms, then explicitly fetch the pinned runtime packages:

```sh
python3 scripts/bootstrap-dependencies.py --with-qnn
./gradlew :app:assembleDebug
```

Without that option, CPU/Vulkan/cloud modes build, but selecting the QNN/NPU backend cannot work. The QNN package does not make the model export compatible with other Qualcomm generations. [THIRD_PARTY.md](THIRD_PARTY.md) explains the separate runtime/model licenses.

## Run

1. Download the desired models and choose source/output languages. Local mode uses local inference after downloads; cloud mode uses ElevenLabs.
2. For SIM mode, start Shizuku, authorize this app, allow phone-state access and optionally allow its overlay. Restart Shizuku after a phone reboot.
3. Arm the desired mode before placing or answering a call and wait for readiness. Stop releases the engines/session; stopping this app does not hang up the call.
4. Microphone mode is available without Shizuku outside a SIM call.

For cloud ASR, the tested key scopes were Speech to Text, User: Read and Models: Read. Agent mode additionally needs access to your owned agent and the relevant ElevenAgents operations. Remote agent settings still govern its language, voice and behavior.

## Verification

The publication snapshot was built separately from the development checkout: 37 core tests passed, Android debug APK and lint succeeded with 35 warnings and zero errors, and both media projects passed TypeScript checks after installation from their lockfiles. The default build and optional QNN dependency bootstrap were checked. See [publication verification](docs/publication-verification.md).

The final development record has 37 passing core tests and successful APK/lint builds. The latest agent suite had 19 passing device cases, one skipped account check and one failing overlay-test selector. After fixing that selector, the isolated real-overlay test passed above another app. A complete all-green device suite was not rerun together afterward because the phone locked. The mid-phrase live-agent failure is unresolved.

CI runs the JVM core suite and TypeScript checks for both media projects. Hardware inference, vendor permissions, modem routing, real cloud quality and Android overlays are outside CI coverage. Earlier device runs and their limits are summarized in [the evidence index](docs/README.md).

```sh
./gradlew :core:test
./gradlew :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Instrumentation restarts the app process. Never run it during a call. Some device tests require installed models, an unlocked screen, granted permissions or an explicitly enabled cloud/account test. The development Serbian IVR audio fixture is not published; tests that read `app/src/androidTest/assets/test-serbian.pcm` require your own appropriately licensed local fixture. Broad device-test commands are not guaranteed to pass on a fresh phone.

## Data and credentials

API keys are entered on the phone and encrypted using Android Keystore in no-backup storage. They are not in this repository. Normal caption history is in memory. A separately armed debug recorder can persist PCM/captions locally; no development call recordings or private transcripts are distributed here. Cloud modes send audio and relevant instructions to the selected provider.

Model weights, QNN runtime binaries, APKs, call fixtures, private account IDs, local environment files and build outputs are excluded from the source snapshot. The included promo soundtracks are synthetic fictional material.

## License and maintenance

Project-authored code is MIT licensed. Vendored code, fonts, model weights, provider outputs and proprietary runtimes retain their own terms; the root license does not relicense them. NLLB model/tokenizer licensing includes a noncommercial restriction. Remotion and ElevenLabs have their own usage terms.

The repository is archived to make the maintenance status visible. Forks are welcome. No future fixes or releases are promised.
