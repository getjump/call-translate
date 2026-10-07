# OnePlus 15 verification - 2026-10-07

Build CPH2747_16.0.10.500(EX01), Android 16, arm64. Updated debug APK installed successfully with previously downloaded models preserved. Tests: 12 JVM, 11 Android device checks passed. Build and lint passed before installation. Fresh whole-project review completed; important findings fixed and affected checks repeated.

## Confirmed

- User authorized Shizuku normally after enabling the OxygenOS developer setting. The package API_V23 permission is granted.
- App-owned Shizuku UserService binds, reports uid=2000, starts a downlink pipe, delivers PCM bytes and stops. This was tested outside a call, so it is not proof of actual call speech.
- Whisper base and small Q5 transcribe saved real Serbian IVR audio on CPU and Vulkan.
- NLLB translates using the correct production Serbian token, preserves the test number/negation and generates Russian captions through the app's complete worker.
- Automatic input mode runs. The base model identified this Serbian fixture as Croatian; related language identification is imperfect. Language can be pinned manually during a call.
- Old-session outputs are rejected after replacement. Cancellation is preserved before JNI entry and checked between NLLB model loads.
- Source/output pickers survive Activity recreation, with test settings restored. Overlay permission is allowed on this phone.

## Timing on a 10-second saved IVR sample

| Model/backend | ASR time | Observation |
| --- | ---: | --- |
| Whisper base CPU | 1783ms | Fast, but important word errors |
| Whisper base Vulkan | 5704ms | Same transcript, slower on this fixture |
| Whisper small Q5 CPU | 6193ms | Better Serbian transcript on this fixture |
| Whisper small Q5 Vulkan | 6049ms | Similar latency to CPU |

NLLB numeric/negation fixture: 461ms, output "Вы не приходите в 15 часов.". Full base/Auto/Russian pipeline including model verification/startup: 7928ms. Process PSS at completed full-pipeline caption: 2547916KiB (~2.43GiB), not a measured peak. Timing and accuracy are based on a single sample, not a benchmark suite.

## Still to validate

Actual live SIM speech -> translated overlay in this new APK, optional uplink labels, paused overlay/resume while a call is active, and behavior after re-enabling OxygenOS permission monitoring. Telegram/WhatsApp, NPU and voice injection are not implemented/validated. Turbo/tiny weights are listed but have not been tested on this phone.

## Conversation and latency update

The latest build passed 15 JVM tests and build/lint. The final affected device batch passed 4/4, including original-before-translation, same-ID update, reused warm engines, direct stale-reader-token rejection, history recreation/manual scrolling and short-context CPU/Auto/Vulkan ASR. The preceding 6/6 device batch also rechecked cancellation and Shizuku binding. The initial UI assertion raced asynchronous broadcast delivery; after waiting for the actual render it passed. The real phone UI screenshot was inspected.

Latest same-fixture CPU pair: full context 4837ms, short context 2329ms on 10s audio. Four-second chunks took 1291-1345ms, with boundary word errors. Short-context Auto: 4337ms, sr. Short-context Vulkan: 4194ms. Model preparation measured 3281-4312ms with warm filesystem caches; reboot-cold startup was not measured. Warm session replacement produced two original events but only the new session's translation, without another ready/load event. These are diagnostic fixtures, not full live-call latency or accuracy benchmarks.

Production now uses a four-second window and matching encoder context, shows originals before translation and retains engines while armed. Auto fixes a language after repeated evidence per side. Final real SIM overlay and live Auto lock remain pending; the user manually reported translated text on the preceding build. Research alternatives are in streaming-research.md and are not implemented.
