# Voice agent integration verification - 2026-10-07

## Installed behavior

The main screen opens a dedicated ElevenLabs agent screen. The app reads owned agents and named voices with the key already stored on the phone. Agent creation requires named-voice selection, configuration review and a separate human confirmation in the app. Creation does not start a conversation.

A task before a call is optional. Empty preferences produce an inbound objective: listen first, identify caller and purpose, ask short clarifying questions, follow private owner instructions as they arrive. The agent speaks the interlocutor language, currently Serbian by default. The owner's target language remains separate. Remote agent settings govern the voice and conversation behavior.

The microphone demo uses local microphone and speaker. Experimental SIM mode waits for an answered call, captures only downlink through Shizuku, and sends agent PCM to the system call-uplink injection track. The owner can send private text instructions during a connected conversation and request microphone mute. Private voice coaching is not implemented. The agent page displays original dialogue and private notes; the separate translation mode retains ASR plus progressive local NLLB translation.

Call Translate and the voice service stop one another on explicit mode changes. HTTP requests are cancelled at hangup or stop. Stale callbacks cannot publish to a new session. Interruption flushes old playback before a new response. Playback accepts TTS delivery bursts with a bounded 60-second PCM budget and no prefill delay. Normal cloud completion stops input and drains pending audio; user stop, call hangup and errors stop immediately. Microphone mute restores its original state only while the service still owns the change; actual restoration is checked and a failure remains visible.

## Fresh verification

- Core: 33 tests, zero failures. Includes interruption ordering, concurrent enqueue, queue limits, fast TTS bursts, normal completion and shutdown.
- Build: debug APK, Android test APK and lint passed. Lint has zero errors and 38 warnings, including internal API compatibility and existing localization/layout/dependency warnings.
- OnePlus 15 / Android 16: 16 targeted instrumentation tests passed. Includes 12 synthetic API/WebSocket tests, two audio tests using a fake call route, agent page startup and a read-only account capability check.
- Account capability check: owned agents 0, voices 54. No agent creation, conversation start or audio upload occurred during these tests.
- Shell probe on the phone: UID 2000, PSTN interception capability true, mode normal. Framework uplink injection method exists. Opening injection outside a SIM call is rejected before track creation.
- Installed APK SHA256: 7fa5689c1a52869b7784d0d14493e128220b354a933b847f1b8082ad6c77c513.

## Live validation still required

No live SIM audio was injected during verification. A peer must confirm that agent speech is audible, then that owner mute stops the physical microphone while agent speech remains audible. Hardware playback-head behavior at normal cloud completion also needs that test. A successful fake-route test or PSTN capability flag does not establish those results.

Create or select an agent in the app before the first live test. For the default Serbian draft the app uses PCM16 mono at 16 kHz, signed-URL authentication, no external tools, an empty initial greeting and a ten-minute conversation limit. A second private voice channel requires a separate routing experiment; global system mute may silence its input.

## Primary references

- Android call interception and uplink AudioTrack: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/AudioManager.java
- ElevenLabs client events: https://elevenlabs.io/docs/eleven-agents/customization/events/client-to-server-events
- ElevenLabs agent list: https://elevenlabs.io/docs/eleven-agents/api-reference/agents/list
- ElevenLabs native output buffering: https://github.com/elevenlabs/unity/blob/main/Runtime/Native/UnityAudioSourceOutput.cs
