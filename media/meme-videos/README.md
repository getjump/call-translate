> Historical promotional concept, not a demonstration of reliable live SIM-agent behavior. The parent project is suspended. Audio is synthetic and fictional; no user call recording is included.

# Call Translate telephone meme videos

Two original illustrated vertical product films, 19 seconds each, 1080x1920, 30 fps. React / Remotion 4.0.527. All characters are adults. The revised delivery follows the supplied clean product-video style and keeps the phone interface central.

## Films

1. WaterToBeer: the hero rehearses Vodu, molim, calls a Serbian beverage-delivery service, panics and says Pivo, molim. The call confirms beer. He privately types a water task into Call Translate; the concept agent says Vodu, molim into the phone call.
2. PerfectPhraseWrongWish: the hero wants beer but masters Vodu, molim. He says it correctly by phone, and the call confirms water. He privately types "Хочу пиво" into Call Translate. The concept agent says Pivo, molim into the call.

No cafe, face-to-face conversation, service employee in the room or physical delivery is shown. The order payoff is a confirmation inside the live telephone interface. Serbian text is exact: Vodu, molim. = Воды, пожалуйста; Pivo, molim. = Пиво, пожалуйста.

## Creative direction

Restrained paper/navy/blue/mint system, simple backgrounds, central device, readable text, subtle shadows and a short feature close-up. Comedy comes from the wrong phrase and the wrong goal. Waveforms identify live dialogue; final app frames settle. storyboard.md provides scene intent/copy/timing. edit-notes.md explains edits.

Assumptions: fictional delivery service, original adult cartoon character and simplified plausible concept screens. No product screenshots or brand assets were supplied for these meme films.

## Reproduction

The rendered MP4s have burned-in captions and a full audio mix. Source artwork is procedural SVG inside React. Font files and cached audio are included. No credentials are included or needed to render.

Run npm install, then npm run typecheck. Set REMOTION_BROWSER_EXECUTABLE to a compatible Chrome headless shell executable. Set CALL_TRANSLATE_OUTPUT if needed. Preview with npm run studio and render with npm run render. node render.mjs --stills creates representative keyframes.

The original build reused a read-only Remotion runtime installed under The Loop. No original The Loop files were changed.

## Audio and product scope

Short Russian/Serbian ElevenLabs v3 assets were generated with user authorization. The hero and concept agent use prerecorded synthetic voices. Assets are reused for this revision. Rapid rehearsals preserve pitch. Transmitted Serbian phrases receive telephone EQ. The two Russian private task assets remain cached but are not played in the final mixes.

Music and SFX are original procedural synthesis: plucked melody, bass, taps, telephone ring, whooshes, scratch, boing and confirmation chimes. No copyrighted music, real calls or recordings are used.

Agent scenes/end cards say "Концепт голосового агента / Call Translate + ElevenLabs". The user types the explicit goal before agent speech. The films do not prove deployed SIM-call audio injection.

## Verification

TypeScript check, visual inspection of 24 representative keyframes, local cached Whisper on key original voice assets, ffprobe metadata, full MP4 video/audio decode and loudness checks. metadata.json records fresh final-file hashes and results. ASR is a text sanity check; a native-speaker pronunciation review was not performed.
