# Progressive recognition research - 2026-10-07

Goal: understand Serbian SIM callers quickly on OnePlus 15, offline, with configurable languages and model selection. This note proposes experiments. No hybrid recognition or speculative decoding is enabled in the app.

## What failed with the smaller model

On the saved 10-second Serbian IVR fixture, Whisper base was fast but made material errors: "mobilnoj telefoniji" became "modemoj telefoniji" and "pritisni taster 1" became "pred DisneyTaster jedan". Translation inherited the wrong source text. Small Q5 recognized these phrases correctly with the longer context. This is one sample, not a general word-error-rate comparison.

The initial small Q5 CPU measurement was 6193ms. Controlled comparison of full versus short encoder context gave 6407ms versus 2688ms on identical audio. A later run gave 4956ms versus 2274ms. Four-second chunks with the short context took 1165-1534ms, but some words degraded and chunk endings were incomplete. A first automatic-language pass on the full 10-second sample took 4096ms and detected Serbian. The measurements are sensitive to warm caches, thermal state and other work; they do not promise call latency.

The short-context change addresses encoder work. Reducing audio-window duration alone would have retained the old expensive 30-second encoder context and could have increased backlog.

## Viable approaches

| Approach | Practical value | Cost and limitation |
| --- | --- | --- |
| One small model, growing audio buffer and LocalAgreement | Immediate provisional original, then stable text with more context; avoids two sets of ASR weights | Repeated encoding still consumes CPU. Update cadence must exceed actual ASR plus translation cost, and buffer length must be bounded |
| Base preview, small refinement | Earlier provisional original and optional draft translation; improved final transcription from original audio | Adds a second ASR pass, memory, energy and possibly a second translation. Refinement must not delay new speech |
| Speculative decoding | A small assistant proposes tokens which a target model verifies; can reduce final decoding time without changing target output under the algorithm's assumptions | Requires runtime-level support and compatible tokenizers. It does not inherently provide a separate user-visible preview. The current Whisper.cpp integration has no assistant-model API |

Recommendation: compare progressive small Q5 against a base -> small cascade before implementing the cascade. Do not treat a larger model as a text-only corrector: it must receive the original audio, since lost numbers or negation cannot be recovered reliably from the draft transcript.

For the one-model experiment, start with updates every 3-4 seconds, a bounded 8-12-second rolling buffer, a mutable trailing hypothesis, and a stable prefix confirmed by successive passes. These are experiment parameters, not measured optimal values. A stable language should be shared across passes. Voice activity boundaries can trigger finalization without cutting a word at an arbitrary frame boundary.

For a two-model experiment, use base for provisional output and small Q5 for refinement at phrase boundaries or when compute capacity is available. Prioritize new speech over corrections. Limit pending corrections, and discard obsolete work. Translation should use utterance ID plus ASR revision; a translation of revision 1 must never overwrite a corrected revision 2. Clear or mark an old translation while a corrected source is being translated. Update one chat bubble rather than append a duplicate.

A draft translation can change meaning, especially numbers and negation. Show a visible draft/final state, and avoid repeatedly translating every unstable token. Translate a stable prefix or pause-sized phrase, with optional preview translation as a separate configurable behavior.

## Evidence and portability

- [Whisper-Streaming](https://github.com/ufal/whisper_streaming) implements LocalAgreement: confirm a prefix after consecutive hypotheses agree. Its authors explicitly warn that naive fixed windows can split words. The [paper](https://aclanthology.org/2023.ijcnlp-demo.3/) reports 3.3 seconds mean English latency on NVIDIA A40; this is not a phone benchmark.
- [SimulStreaming](https://github.com/ufal/SimulStreaming) extends Whisper streaming with AlignAtt. Its decoder uses attention to stop near the end of available audio and resume with the next input. It also describes LocalAgreement as simpler to implement. Porting AlignAtt into this JNI/Whisper.cpp path would be a distinct runtime project, not a drop-in dependency.
- [Hugging Face's speculative Whisper benchmark](https://huggingface.co/blog/whisper-speculative-decoding) shows about 1.9x on Dutch with tiny assisting large-v2, and about 2.2x for the English distilled setup. These are Transformers results; no comparable Android/Adreno result was established here. The [Distil-Whisper repository](https://github.com/huggingface/distil-whisper) describes its published checkpoints as English-focused and recommends multilingual Whisper Turbo for multilingual recognition.

## Acceptance criteria for a phone experiment

Replay the same labeled Serbian call samples through optimized small, progressive small and base -> small. Include informal speech, names, numbers, negation, silence/noise and Serbian/Croatian ambiguity. Report first-original latency, first-translation latency, final latency, error/correction rate, queue age and dropped audio. Run sustained input long enough to observe thermal throttling, memory and energy behavior. The required condition is sustained processing faster than incoming audio with headroom; a single fast inference is insufficient.

The research supports feasibility of provisional recognition followed by refinement. It does not yet establish which approach is best on this OnePlus or that a two-model cascade outperforms optimized small Q5.
