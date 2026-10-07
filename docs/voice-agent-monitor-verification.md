# SIM voice agent monitoring - 2026-10-07

Call Translate now sends an agent response to two outputs: the existing Shizuku PSTN uplink injection and a local voice AudioTrack. Local output follows the current call route; the app does not change the SIM audio mode or select a different communication device. Agent input remains VOICE_DOWNLINK, rather than the phone microphone or the monitoring PCM.

The SIM observer already starts on OFFHOOK for incoming and outgoing calls. The button and waiting status now explicitly name both directions. The agent transcript appears above account setup when a session is running or has history, labels caller and agent replies, and remains scrollable after a session ends or the Activity reopens. Microphone tests label the owner as 'Ты'. Private text instructions stay in the transcript as private notes.

Each output maintains its own PCM write cursor. Partial and zero-byte writes cannot duplicate or skip samples in the other output. Both outputs flush on interruption and must drain before normal conversation closure. Stop and hangup still abort immediately.

## Short response diagnosis

On the OnePlus, a 100 ms response supplied 1600 PCM frames but the local AudioTrack waited for its default 3864-frame start threshold: playback head 0, state PLAYING, buffer 3864. This reproduced a drain timeout. Both local and injection tracks now request a one-frame start threshold. Android documents the initial buffer-capacity threshold and the public setter in the [AudioTrack reference](https://developer.android.com/reference/android/media/AudioTrack#setStartThresholdInFrames(int)).

## Fresh verification

- 37 core tests passed, including identical PCM after partial writes, interruption between outputs, invalid driver counts and bounded handling of a stalled output.
- 19 Android tests passed on the connected OnePlus; one account-read test was skipped because that optional check was not requested.
- Real local AudioTrack playback starts in the simulated SIM adapter test and drains a short response. The PSTN adapter in that test is a fake Binder, not an actual modem route.
- Both transcript feeds passed scrolling/reopening checks. The agent transcript precedes setup controls.
- Debug APK, instrumentation APK and lint build succeeded. Lint: 0 errors, 38 existing warnings.
- The installed main APK SHA-256 matched the built APK. Keys and selected agent preferences survive replacement installation.
- Tests made no real cloud agent conversation or audio upload. Protocol tests use synthetic HTTP/WebSocket fixtures.
- A read-only audit found no blocking regressions in PCM fanout, interruption, lifecycle or live transcript scrolling.
- After unlocking, the app loaded the existing ElevenLabs agent through its saved key and entered SIM waiting mode. An earlier outgoing test happened before that mode was enabled; no agent service was running during that call.

Evidence and installable APKs: `<local-artifact-directory>`.

## Remaining live check

Real local audibility during MODE_IN_CALL, remote audibility of uplink injection, mute behavior and any echo require a real SIM call. The automated tests establish PCM delivery, local playback outside a real modem call, lifecycle and UI behavior. They do not establish what a remote subscriber hears.

For the live check, enable 'SIM: входящие и исходящие', place a normal call to a willing test counterpart, and return to the agent page or its notification to see the transcript. Confirm both parties hear the agent, then check owner mute and interruption. If microphone leakage causes echo, use the call's earpiece/headset or owner mute while investigating the actual route.

## Live follow-up and agent overlay

The owner heard an agent response during a real outgoing SIM call, but it stopped midway through a phrase. This establishes local audibility during that call. It does not establish remote audibility or a complete working dialogue. Existing diagnostics cannot distinguish cloud interruption, audio failure or a call-state transition for the reported truncation.

The earlier build had an agent transcript only inside AgentActivity. VoiceAgentService now uses CaptionOverlay as soon as a SIM session starts, before cloud setup completes. It shows connection status, the common caller/agent transcript and actions to open the dialogue or stop the agent. On failure or normal cloud closure, it keeps the final status and transcript until hangup; stop/hangup removes the window. Missing overlay permission leaves the in-app transcript and notification available, with a permission shortcut on the agent page.

Call-state, session ready/close/failure, first received audio, interruption and drain completion events are now logged without PCM, transcript, API credentials or signed URLs. Audio receipt does not prove playback or remote audibility. Shizuku callbacks dispatch to the main thread before changing audio/session/UI state.

The new native overlay test failed against an empty implementation as expected. The first implemented run had two UI failures while the device was locked: window focus NotificationShade, mInputRestricted=true and mDreamingLockscreen=true. An unlocked suite then passed 19 checks and skipped one optional account check, but its overlay selector failed: Android accessibility windows did not provide the expected title.

The selector now identifies the actual overlay by its package and actions. The isolated native test passed on the unlocked OnePlus, including caller/agent text, status, both actions and removal. A concurrent WindowManager probe confirmed mHasSurface=true, isOnScreen=true and isVisible=true. A subsequent full-suite retry was safely skipped because the phone had locked again. Thus the overlay is verified independently; the complete suite was not rerun green together after the selector change. Core tests and build/lint remained green.

No further live agent call was started. VoiceAgentService is not armed while the owner considers a media-subtitle use case. The truncated live response remains unresolved.
