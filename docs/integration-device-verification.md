# Integrated NPU and ElevenLabs device verification

Date: 2026-10-07. Target: OnePlus 15, CPH2747_16.0.10.500(EX01), SM8850/HTP81, Android 16. Package: dev.getjump.calltranslate.

## Implemented and installed

The main application now has a local/ElevenLabs ASR provider switch. Local Whisper large-v3-turbo uses the pinned Qualcomm w8a16 QNN package through a private :asr_npu service. QNN ORT 1.29 is isolated from main-process standard ORT 1.30 used by NLLB. CPU fallback is disabled for the selected NPU model. The model picker/download path verifies both the ZIP and each expected extracted file by length and SHA-256. All five production-private model file hashes match the pinned manifest.

Recognition and translation use separate bounded queues. Original text appears before translation, which updates the same conversation entry. Local phrases end after 600ms silence or at a 10-second cap, with 300ms pre-roll and full encoder context. This replaces the previous independent 4-second/context-shortening path. Models load in parallel when the service is armed and remain resident until Stop. Source/target/session changes reject obsolete work. Cloud partials update a draft; only final segments enter local NLLB translation.

ElevenLabs API credentials are entered in the app and encrypted with Android Keystore in no-backup storage. GET /v1/user verifies authentication before authenticated GET /v1/models. The realtime picker excludes batch ASR and TTS. The current documented realtime model is scribe_v2_realtime; if omitted by the account catalog it is clearly labeled as coming from API documentation, not claimed as returned by that account. Automatic catalog refresh runs when cloud settings open with a saved key. Invalid replacement preserves the previous key/catalog. Provider/key/model controls cannot change during capture. Audio streams start only for accepted active capture, never while merely waiting for a SIM call; there is no automatic cloud fallback.

Required key scopes: Speech to Text, User: Read, Models: Read. This is a user API key flow; no third-party OAuth delegation or token broker is implemented. Sources: [Realtime API](https://elevenlabs.io/docs/api-reference/speech-to-text/v-1-speech-to-text-realtime), [Models API](https://elevenlabs.io/docs/api-reference/models/list), [User API](https://elevenlabs.io/docs/api-reference/user/get).

## Fresh verification

- 19 JVM core tests passed, including speech segmentation, split PCM bytes, model integrity and conversation/session contracts.
- Debug APK, instrumentation APK and Android lint passed after final review fixes.
- Six integrated NPU tests passed: Serbian/manual and Auto recognition, 30-second SharedMemory transport, cancellation/reuse, in-flight close/replacement, and simultaneous NPU/NLLB with runtime process separation.
- Real NPU -> NLLB pipeline passed twice. Two originals appeared before their same-ID Russian translations, preserving the keypad number.
- Ten fake ElevenLabs protocol/API/credential tests passed on the installed final build. They cover authentication order/errors, catalog filtering/fallback labeling, encrypted persistence/removal, bounded PCM sending, partial/final IDs, delayed language metadata, duplicate old metadata versus identical new text, ambiguous timestamps, Javanese alias normalization and callbacks after close.
- Existing replacement and actual Shizuku binding/capture tests passed. Capture outside a call confirms FD/PCM plumbing, not fresh live-call speech quality.
- Initial device batch: 16 passed and MainFlowTest failed to resume during recreation. Fresh dumpsys showed secure keyguard with a sleeping screen; this environmental failure is retained in the raw log. After user unlock, all five UI tests passed (four cloud-settings cases plus source/output recreation). A subsequent cleanup regression passed and the actual persisted XML retained Serbian/Russian after instrumentation finished. The test now waits for UI idleness and synchronously commits restored languages; asynchronous cleanup had left Auto/English behind.
- Independent review found three P2 issues (old metadata matching new text, key-operation/capture race, language alias). All are fixed with regression tests; scoped re-review found no remaining concrete blocker.

Final headless batch: OK (11 tests), 17.085s, in call-translate-reviewed-headless-tests.log. This comprises ten cloud cases and the real integrated pipeline. Final UI batch: OK (5 tests), 4.176s, in call-translate-reviewed-ui-tests.log; cleanup regression: OK (1 test), 0.948s, in call-translate-ui-cleanup-device.log. No real ElevenLabs account/key/quota/quality test has run.

## Same-fixture integrated measurements

The saved 10-second Serbian IVR fixture recognized as: "Za informacije o mobilnoj telefoni i bezicnom kucnom internetu koji koristi SIM karticu, pritisni taster 1." (ASCII transliteration here; raw results preserve Serbian letters.)

Manual Serbian NPU ASR: 1153ms. Auto: 1127ms, detected Croatian (hr), demonstrating that close-language ambiguity remains. Final two-phrase pipeline: ASR 1046ms and 865ms; first translation delivered 2040ms after submission, second 2718ms after submission; both complete at 2801ms. Queue wait is included in total delivery time. These are loaded-model, same-fixture measurements, not end-to-end live-call latency, broad accuracy or sustained thermal evidence.

The main-process PSS snapshot after this pipeline was 2290371KiB; it excludes the separate NPU process. Translation remains CPU NLLB and model residency remains substantial.

## Remaining checks and boundaries

Settings/persistence tests passed after unlock. Final persisted configuration is local / whisper-turbo-qnn / NPU / sr / rus_Cyrl; other preferences were preserved. Installed main APK SHA-256 matches the built file: 45a30caba8f02994e6ff8f46a0720c73236a5d4248f5d7ea832c313164f75478. Final deployment: MainActivity was opened and its visible SIM action pressed. The UI reports "Модели готовы. Ожидаю SIM-звонок" with Shizuku access granted and Serbian selected. Fresh dumpsys confirms CallService is foreground (notification 10) and the separate :asr_npu service is bound. Screenshot was visually inspected. The app is ready for a user-started live call; that call has not been performed in this integration run. Live SIM capture with NPU, broad language/number/address accuracy, actual cloud authentication/streaming and sustained battery/thermal behavior require a representative user-started test. The archive download/extraction path was inspected and its pinned ZIP entries/hashes verified against the local official package, but a complete network download and failure-injection suite for ZIP extraction have not been run.

No TTS SIM uplink, speaking agent, billing/account change, outgoing call, developer-setting modification, commit or publication was performed. Existing saved research is retained as historical evidence; this document supersedes its earlier "not integrated" status.

Final verification totals: 19 core cases and 24 distinct device cases passed across targeted batches. The initial locked-screen MainFlow failure is retained and passed after unlock. Artifacts include final main/test APKs, build/device logs, filtered fixture benchmarks, model hashes, source manifest/archive and this report. Persistent source was synchronized with baseline/concurrent-edit guards and matching hashes. Main-app model files remain installed; only the temporary external model copy is removed.
