# NPU and ElevenLabs integration

Date: 2026-10-07. User explicitly requested integration and APK installation for live testing. Existing NPU probe and two-stage caption design are approved inputs. No commit, release or publication.

Goal: select Whisper turbo HTP locally or ElevenLabs streaming ASR, retain local NLLB translation and a scrollable same-ID conversation feed, and test the integrated APK on OnePlus 15.

Design: isolate ORT QNN 1.29.0 in a bound `:asr_npu` service with shared-memory audio, retain standard ORT 1.30.0 in the main process for NLLB. Recognition and translation use separate bounded queues and session/revision guards. Local speech segments end on silence or a 10-second cap, always use full encoder context, and replace the unsafe independent 4-second path. Cloud streams consume 100ms PCM including silence only during active capture. Translate final cloud segments; partials update the same caption first.

Independent work ownership:
- NPU worker: new `ml/npu` Java/AIDL, `cpp/npu`, packaged QNN libraries/assets/licenses and device tests. Public `NpuEngine(Context, File)`, `transcribe(float[], String) -> String[2]`, `cancel`, `close`. Root adds service/build registration and catalog installer.
- ElevenLabs worker: new `cloud` Java and device tests. Keystore `CredentialStore`, authenticated `ElevenLabsApi.verifyAndList`, `ElevenLabsStream` with stable per-segment IDs, partial/final callbacks and bounded sending. Root adds OkHttp and settings/UI.
- Root: model ZIP validation/atomic install, provider/settings UI, two-stage worker, speech segmentation and integrated build/device verification.

Constraints: preserve existing user files and downloaded models. No silent cloud fallback or automatic provider change. Key never enters APK, logs, URL or backup. Only a successfully verified key populates the account model catalog; incompatible TTS/non-realtime ASR cannot be selected for realtime. If account catalog omits the documented realtime model, label its source explicitly. Local mode never sends audio to cloud. Source/target changes, pause, hangup, provider change and worker replacement reject obsolete callbacks. Normal UI prompts, not developer-setting bypasses.

Verification:
- Core tests cover PCM odd-byte boundaries, silence/pre-roll/cap segmentation and existing caption/session/integrity contracts.
- Device NPU tests cover manual Serbian, Auto, cancellation/closed engine and simultaneous main-process NLLB.
- Fake cloud wire tests cover authentication errors, model filtering, partial/final same-ID behavior, final deduplication, missing language and closed-stream callbacks. Real ElevenLabs call requires user key and cannot be claimed without it.
- Integrated pipeline fixture must publish original first, append translation to the same ID, reject stale replacement results and load the two runtimes without crashes.
- Build debug/main test APKs, lint, inspect native packaging, install on connected OnePlus, copy verified NPU model files into production private storage, select local NPU/Serbian/Russian, and open the app for user-started SIM testing.

Checklist:
- [x] NPU service and client.
- [x] Cloud authentication/discovery/streaming.
- [x] Picker and verified ZIP model install.
- [x] Two-stage inference and local phrase segmentation.
- [x] Verification, persistent source sync and installed APK.

Remaining empirical boundary: broad multilingual ASR quality, last bad live-call reproduction, sustained thermals and real account quota/streaming quality require user testing. No TTS uplink or agent integration.

Implementation, installed headless/UI tests, persistent source sync and foreground model readiness passed. The app is configured for local Turbo/NPU, Serbian -> Russian and waiting for a user-started SIM call. See [verification record](integration-device-verification.md).
