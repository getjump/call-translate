# Editing the project

- Main headlines, goals, Serbian phrases and color palette: src/content.ts, COPY and COLORS.
- Scene starts and total length: src/content.ts, TIMING. Values are frames at 30 fps. Keep audio starts in mix.py synchronized when changing timing.
- Scene layouts and SVG artwork: src/Meme.tsx. Components are Study, Brain, CallScreen, Phone, Payoff and End. The call screen and app reuse the same central-device dimensions.
- Typography, device proportions, card spacing and shadows: src/style.css. Theme colors come from COLORS as CSS custom properties.
- Voice asset references, music and sound timing: mix.py. Cached ElevenLabs files live in public/audio. No API call or credential is needed to render.
- Rebuild audio with python3 mix.py (requires numpy and ffmpeg). It generates original music/SFX, telephone EQ and a mastered 48k stereo mix.
- Preview with npm run studio. Render with npm run render. Set REMOTION_BROWSER_EXECUTABLE to Chrome headless shell and CALL_TRANSLATE_OUTPUT to a destination if needed.
- Render stills with node render.mjs --stills. contact_sheets.py assembles representative frames; adapt its destination when moving the project.

The existing rapid rehearsal voices are reused. Private typed Russian tasks are deliberately silent. ElevenLabs agent speech starts after the entire task has been typed. Final frames freeze typing, waveforms and device motion so the closing benefit can be read.

The images are simplified concept UI rather than screenshots of an already deployed SIM injection feature. Retain the concept label when changing the closing copy.
