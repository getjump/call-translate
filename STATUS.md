# Project status

Status: **suspended and unmaintained**, as of 2026-10-07. Further development by the original author is unlikely.

This snapshot preserves a partially working Android experiment. It is not a production call-translation or voice-agent product. No active roadmap, support commitment or release schedule exists. The GitHub repository is archived; continued development should happen in a fork.

## Open problems at suspension

- The live SIM voice agent produced a locally audible fragment and stopped mid-phrase. The cause was not established. Reliable delivery to the remote caller is unverified.
- Shizuku/OxygenOS setup is intrusive and device-specific. Other phone/firmware combinations are unvalidated.
- Local recognition quality, hallucinations, related-language detection and startup/translation latency remain inconsistent.
- QNN is tied to one HTP81 export/runtime/device combination. Sustained throughput, heat and battery behavior were not established.
- Mute interaction, echo, durable conversation history, private voice coaching and VoIP-call routing are unfinished or unverified.
- System-media translated subtitles were only discussed as a possible direction. No such capture mode exists in this snapshot.

## Evidence rules

Unit/protocol tests establish the exercised contracts. Synthetic PCM/fake modem adapters do not prove subscriber audibility. Single-fixture benchmarks do not establish general recognition accuracy or sustained real-time behavior. Promotional agent scenarios are concepts.

README.md and this file supersede optimistic or outdated statements in historical development notes. Historical notes are retained as evidence, not as support promises or a current deployment checklist.
