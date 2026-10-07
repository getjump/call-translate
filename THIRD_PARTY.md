# Third-party code, runtime and media sources

The root MIT license covers project-authored code. It does not replace any upstream license or provider terms. Native source revisions are recorded in [vendor-lock.json](vendor-lock.json); their license files remain in each vendor directory.

| Source | License / terms | Use |
| --- | --- | --- |
| [whisper.cpp](https://github.com/ggml-org/whisper.cpp) | MIT, `vendor/whisper.cpp/LICENSE` | Vendored ASR and GGML CPU/Vulkan implementation. The project patch is retained under `patches/`. |
| [SentencePiece](https://github.com/google/sentencepiece) | Apache-2.0, `vendor/sentencepiece/LICENSE` | Native tokenizer code; its bundled dependencies retain their own licenses. |
| [Vulkan-Headers](https://github.com/KhronosGroup/Vulkan-Headers) | Apache-2.0/MIT as stated upstream, `vendor/Vulkan-Headers/LICENSE.md` | Build headers. |
| [SPIRV-Headers](https://github.com/KhronosGroup/SPIRV-Headers) | Khronos license, `vendor/SPIRV-Headers/LICENSE` | Build headers. |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | MIT | Standard Android 1.30.0 from Maven; QNN Android 1.29.0 is downloaded separately and its library is renamed for process isolation. License retained under `licenses/npu/`. |
| [whisper-htp-android](https://github.com/thedevguy/whisper-htp-android) | MIT, `licenses/npu/whisper-htp-android-LICENSE.txt` | Native mel extraction and fixed-cache decode adapted from commit `b33eae5c9985db06a4fb520272c321dea6849907`. |
| [Qualcomm QNN runtime 2.50.0](https://repo.maven.apache.org/maven2/com/qualcomm/qti/qnn-runtime/2.50.0/) | Qualcomm runtime terms and notices in `licenses/npu/` | Optional downloaded HTP81 runtime; binaries are excluded from Git. Review the retained terms before fetching or redistribution. |
| [Shizuku API/provider](https://github.com/RikkaApps/Shizuku-API) | MIT | Version 13.1.5 via Maven. |
| [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) | Apache-2.0 | Version 6.1 via Maven. |
| [AndroidX](https://android.googlesource.com/platform/frameworks/support/) | Apache-2.0 | Android annotations and test dependencies. |
| [OkHttp](https://github.com/square/okhttp) | Apache-2.0 | Version 4.12.0 for cloud connections. |
| [JUnit](https://github.com/junit-team/junit4) | EPL-1.0 | Version 4.13.2 for core tests. |
| [RTranslator](https://github.com/niedev/RTranslator) | Apache-2.0 code; model license is separate | Its prepared NLLB 2.0.0 release supplies the downloaded ONNX model files. Decoder/tensor conventions were checked against this implementation. |
| [React](https://github.com/facebook/react) | MIT | Promotional video components. |
| [Remotion](https://www.remotion.dev/license) | Remotion's own license and usage terms | Promotional video tooling; check eligibility for your use. |

## Models and tokenizer

No full model weights are committed. ASR and translation model download URLs, expected sizes and SHA-256 hashes are in `app/src/main/assets/catalog.json`. These model terms are separate from runtime/code licensing.

- GGML Whisper models: [ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp), derived from OpenAI Whisper; see the upstream model card and MIT terms.
- NLLB-200 distilled 600M weights and tokenizer: [facebook/nllb-200-distilled-600M](https://huggingface.co/facebook/nllb-200-distilled-600M), CC-BY-NC-4.0. This includes a noncommercial restriction. The bootstrap fetches only `sentencepiece.bpe.model` at revision `f8d333a098d19b4fd9a8b18f94170487ad3f821d`, with SHA-256 `14bb8dfb35c0ffdea7bc01e56cea38b9e3d5efcdcb9c251d6b40538e1aab555a`; it is then packaged as `sentencepiece_bpe.model` in a local APK.
- QNN Whisper large-v3-turbo w8a16 export: [qualcomm/Whisper-Large-V3-Turbo-Quantized](https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo-Quantized). The implemented path is specific to its HTP81 export. Review the model card/terms before downloading or distributing it.
- The small public Whisper vocabulary and mel-filter assets have provenance documented in `licenses/npu/PROVENANCE.txt`.

## Optional QNN packages

`scripts/bootstrap-dependencies.py --with-qnn` downloads these public Maven AARs and verifies their complete archive hashes before extracting the expected arm64 libraries:

| Artifact | SHA-256 |
| --- | --- |
| `com.qualcomm.qti:qnn-runtime:2.50.0` | `b507656e4031d8aa6a7ab0fecf725b941096737ad7440ad7ea288f7c9cc9d743` |
| `com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0` | `cc4cfd72a47703aee65d8e6ce7448fa7488e3fff6556f9d6d354ec1687347cfd` |

The downloaded packages and installed shared libraries are ignored by Git. Retained licenses/notices do not imply a blanket redistribution grant from this project.

## Promotional assets

The two `media/` projects include fictional synthetic speech generated through ElevenLabs, original procedural music/effects and fonts with their own license files. Provider-generated speech remains subject to the relevant ElevenLabs terms; the root MIT license is not a license grant from ElevenLabs. Speech regeneration is optional and requires your own API key/account. No credentials or private voice/account IDs are included.

Inter and Unbounded use the SIL Open Font License; their license files are retained beside each media project's assets. Upstream native test samples remain part of the vendored upstream source trees under their upstream terms. Development call recordings and the local Serbian IVR test fixture are excluded.
