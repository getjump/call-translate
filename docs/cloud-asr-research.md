# Cloud ASR candidates and ElevenLabs follow-up

Date: 2026-10-07. User requested saving the supplied cloud-provider research and trying an ElevenLabs integration after local testing. User reports an existing Creator subscription. Account access, actual quota and API billing configuration have not been inspected.


Current implementation: ElevenLabs BYOK streaming, authenticated model discovery and local NLLB are integrated in the main app. See [integrated verification](integration-device-verification.md). The research-stage statements below remain historical; actual account and cloud inference are still unverified.
## Status and ordering

The CPU/GPU comparison is complete; see `gpu-npu-research.md`. Qualcomm QNN/HTP is researched but has not run on the OnePlus. ElevenLabs is the first requested cloud candidate after the local test stage. No API key was acquired, cloud inference was executed or production cloud backend implemented in this phase.

The original supplied shortlist and its links are preserved in `cloud-options-user-draft.md`. Treat its model names, prices and subscription entitlements as user-supplied research pending individual validation. The checks below verify the ElevenLabs portion only.

## Verified ElevenLabs path

Scribe v2 Realtime is the speech-to-text product to integrate first. Serbian is listed in the transcription language support. It has interim and committed text, so the existing conversation feed can display an editable draft and later update the same utterance. Translate committed segments initially to avoid translating every changing partial. Keep the existing local translation model for the first comparison. [Speech-to-text capabilities](https://elevenlabs.io/docs/overview/capabilities/speech-to-text)

The public API rate is $0.39/hour. The current Creator column advertises 56 hours of realtime STT when the allocation is used for that product. This is not an additional dedicated STT allowance alongside full TTS/Agents allowances. Legacy subscriptions and remaining shared quota can differ; the user's actual subscription must be checked before showing a remaining-time estimate. The 275 minutes in the supplied draft describe Agents and are not the Scribe limit. [API pricing](https://elevenlabs.io/pricing/api), [subscription billing and shared credits](https://elevenlabs.io/docs/overview/administration/billing)

## OAuth result

No documented public OAuth authorization-code/PKCE flow was found for a third-party app to obtain delegated Scribe access to a user's ElevenLabs account. The official third-party account integration guidance says to use an API key. Google/social sign-in to the ElevenLabs website and workspace OAuth connections for external tools do not establish an OAuth login for our app. This is a documentation finding, not proof that a private partnership flow cannot exist. [Official third-party integration guidance](https://elevenlabs.io/docs/help-center/account/general/how-can-i-use-eleven-labs-with-third-party-services)

The documented client authentication route is a single-use token, minted using an API key and consumed at WebSocket connection. It expires after 15 minutes if unused; this is not a 15-minute session limit. A token broker can keep the long-lived key on a backend. Authentication to our broker could use our own OAuth, but that would not automatically grant access to the user's ElevenLabs subscription. [Single-use token API](https://elevenlabs.io/docs/api-reference/tokens/create), [client/server example](https://elevenlabs.io/docs/eleven-api/resources/libraries/scribe-stt/javascript-scribe)

For the personal prototype, a user-owned, restricted API key is a viable BYOK configuration rather than embedding a shared key in the APK. If implemented, store it encrypted using Android Keystore, exclude it from backups and logs, allow deletion, and mint session tokens as needed. For a distributed application, prefer a broker with authenticated sessions and per-user quotas. This is a proposed integration boundary; no credential storage or broker has been created.

## Wire contract and MVP

Use `wss://api.elevenlabs.io/v1/speech-to-text/realtime` with `model_id=scribe_v2_realtime` and `audio_format=pcm_16000`, matching the current SIM PCM format. The API accepts the API-key header or a single-use token. Send base64 PCM chunks; receive `partial_transcript` and `committed_transcript`. Use VAD commits initially, manual `sr` for the Serbian comparison, then evaluate Auto and secondary-language hints. Language information can arrive later with committed timestamps. [Realtime API](https://elevenlabs.io/docs/api-reference/speech-to-text/v-1-speech-to-text-realtime)

An app implementation should stream small PCM chunks directly rather than waiting for the existing local four-second ASR window. Preserve session/language generation guards, same-ID caption updates, cancellation and bounded queues. Drop obsolete results after pause, hangup or provider switch. Expose connecting, active, reconnecting, authentication/quota failure and offline states. Cloud selection must be explicit; offline local selection should not silently upload call audio or silently spend API quota.

Creator is a cloud mode with provider-side handling of audio. Do not promise zero retention: the realtime API documents zero-retention mode as Enterprise-only. The app must accurately describe the selected provider's data handling rather than applying the offline mode's guarantees to cloud calls. [Realtime logging option](https://elevenlabs.io/docs/api-reference/speech-to-text/v-1-speech-to-text-realtime)

First compare the saved Serbian fixture against local results, feeding the cloud service in real time. Measure first partial, committed latency after the speech boundary, Serbian text, digits/names, disconnect behavior and total Russian-caption delay. Only then validate a user-started SIM call. A prerecorded request sent as fast as possible cannot establish streaming user latency.

ASR, translation and a speaking agent remain separate features. This first cloud MVP only replaces recognition. Sending TTS into SIM uplink is still unproven; an ElevenAgents/SIP route would be a separate telephone architecture and a separate test.
