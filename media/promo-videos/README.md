> Historical promotional concept, not a demonstration of reliable live SIM-agent behavior. The parent project is suspended. Audio is synthetic and fictional; no user call recording is included.

# Call Translate product videos

Three vertical React/Remotion films. The two main videos last 18 seconds; the inheritance scenario lasts 20 seconds. All are 1080x1920 at 30 fps.

The illustrated interface represents the tested translation behavior: incoming call audio, Serbian transcription, Russian draft updates inside the same bubble, final replacement, selectable languages, and phrase-final translation in offline mode. The voice-agent film is explicitly marked as a concept. Its outgoing call scenario shows a private owner instruction during the call, followed by a Serbian agent utterance. It does not establish functioning speech injection into a live SIM call.

The inheritance film first shows an untranslated call ending in confusion and hangup, then explicitly restarts the same call as an alternative scenario using live translation. It does not depict recovering a translation after a finished call. The inheritance call is fictional. It does not portray a real lawyer, customer, or inheritance claim. All voices are generated; no private call recording is used.

## Brief storyboard

| Film          | Time       | Viewer sees and understands                                                                                                                | Copy                               | Connection                                                   |
| ------------- | ---------- | ------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------- | ------------------------------------------------------------ |
| Translation   | 0.0-3.3s   | An incoming delivery call with a Serbian phrase                                                                                            | "Алло? Тебе звонят по-сербски."    | The same sentence becomes a Russian bubble                   |
| Translation   | 3.3-6.2s   | Original and readable translation                                                                                                          | "Теперь понятно."                  | The translation bubble becomes the phone transcript          |
| Translation   | 6.2-12.2s  | Close product view; draft is replaced by final in the same bubble                                                                          | "Перевод в ходе звонка."           | The phone feature expands to language choices                |
| Translation   | 12.2-14.8s | Language selection and accurate offline limitation                                                                                         | "Выбери свой язык."                | Colors and speech mark carry into the close                  |
| Translation   | 14.8-18.0s | A settled brand end frame                                                                                                                  | "Звонок понятен."                  | Hold long enough to read                                     |
| Voice concept | 0.0-3.0s   | Outgoing call to delivery; owner wants an answer                                                                                           | "Не знаешь, как сказать?"          | The owner gives a private instruction during the call        |
| Voice concept | 3.0-6.3s   | Private instruction with Russian voice                                                                                                     | "Уточни, когда привезут заказ."    | The active call continues into the agent response            |
| Voice concept | 6.3-12.0s  | Central phone screen; the agent speaks Serbian into the telephone scenario                                                                 | "Агент отвечает по телефону."      | The transcript settles before a short feature statement      |
| Voice concept | 12.0-14.7s | Goal and wording are distinct                                                                                                              | "Ты звонишь. Агент говорит."       | Brand closing preserves the concept label                    |
| Voice concept | 14.7-18.0s | Settled brand close                                                                                                                        | "Твоя задача. Его голос."          | No download, launch, or live-call claims                     |
| Inheritance   | 0.0-0.9s   | Unknown incoming telephone call, with no translator or caller identity shown                                                               | "Звонят по-сербски. Что он хочет?" | The owner answers                                            |
| Inheritance   | 0.9-5.5s   | The caller speaks Serbian; the owner is confused; no Russian translation or inheritance spoiler appears                                    | "Не понимаю. Сбрасываю."           | A visible red hangup button is pressed                       |
| Inheritance   | 5.5-7.0s   | Audio cuts and the phone shows the completed call                                                                                          | "Звонок завершен"                  | The film moves into an explicit counterfactual               |
| Inheritance   | 7.0-8.0s   | A clean full-screen pivot                                                                                                                  | "А ты мог бы так"                  | "Тот же звонок. Другой сценарий." identifies the alternative |
| Inheritance   | 8.0-17.0s  | The same incoming call restarts inside Call Translate; the Serbian voice and live Russian translation reveal the winery and billion dinars | "Тот же звонок. С переводом."      | The final translated sentence settles                        |
| Inheritance   | 17.0-20.0s | The owner understands what he could have missed; simple product message                                                                    | "Кажется, зря сбросил."            | "Пойми звонок, прежде чем сбросить." closes the film         |

## Preview and render

The delivery includes bundled fonts, voice clips, and finished audio tracks. Rendering does not call ElevenLabs. Install the pinned dependencies once on a new machine, then use the commands below. This production used a read-only symlink to an existing dependency cache.

```sh
npm ci
npm run studio
npm run typecheck
npm run render
python3 scripts/verify.py
```

Set `PROMO_OUTPUT` to override the output directory. Set `PROMO_ONLY=translation-promo`, `voice-agent-concept`, or `incoming-inheritance` to render one composition. Set `REMOTION_BROWSER_EXECUTABLE` if you already have a compatible Chromium executable. The render script uses a cached local Remotion browser when available.

Python audio/verification scripts need NumPy, Pillow and ffmpeg/ffprobe on PATH. The optional speech QA script needs mlx-whisper and its cached `mlx-community/whisper-large-v3-turbo` model. It is run offline; no model upload is used.

## Edit notes

- The first two compositions, scene copy, palette `C`, phone components, and sequence timings are in `src/Root.tsx`.
- The third composition, slate/wine palette, Serbian/Russian phrase pairs, and continuous device animation are in `src/Inheritance.tsx`.
- `src/style.css` defines the bundled Inter and Unbounded fonts. The corresponding SIL OFL notices are in `public/fonts`.
- Replace an asset under `public` and keep its filename, or change the associated `staticFile` reference. Voice clips are in `public/voice`; final mix tracks are in `public`.
- To change duration, adjust both the composition duration and scene timing. Regenerate or trim the matching track to the same duration. One second is 30 frames.
- `scripts/make-audio.py` creates the original upbeat tracks. `scripts/make-inheritance-audio.py` creates the quieter phone scenario track. Both run locally.
- `scripts/generate-voices.py` is optional and makes paid API requests only for missing voice files. It reads `ELEVENLABS_API_KEY` from the environment or an external file selected by `PROMO_ELEVEN_ENV`. No key is included in this project.

## Verification

`validation.json` records resolution, frame count, color space, H.264/AAC codecs, full decoding, measured loudness, true peak, and clipped samples. Contact sheets contain representative full-resolution renders. `speech-qa.json` records the expected and recognized wording rather than hiding recognition discrepancies. Speech recognition is a content check, not a native-speaker accent certification.

Serbian support for the selected Eleven v3 model was checked against [ElevenLabs model documentation](https://elevenlabs.io/docs/overview/models). Four short TTS clips were generated for these three films. Music and UI sounds are original procedural synthesis; no copyrighted soundtrack is used.
