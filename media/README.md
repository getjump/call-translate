# Promotional source projects

These are React/Remotion concepts made during development. They are not recordings of validated live SIM-agent behavior. The parent project is suspended.

- [promo-videos](promo-videos/README.md): translation promo, speaking-agent concept and fictional Serbian inheritance call.
- [meme-videos](meme-videos/README.md): two phone-call jokes about water/beer and a concept agent.

Node.js 22+ and npm are the intended tooling. Each directory has pinned dependencies and its own lockfile. Run `npm ci`, `npm run typecheck`, then `npm run render`. Remotion can provision its own browser; an existing executable can be selected with REMOTION_BROWSER_EXECUTABLE. Both projects' audio/QA tools need ffmpeg/ffprobe on PATH; optional Python tools may also need NumPy/Pillow/faster-whisper.

Pre-generated fictional synthetic soundtracks and fonts are included so rendering does not require a provider key. The optional voice generator uses ELEVENLABS_API_KEY only when explicitly run and a clip is missing; that can incur provider charges. No development call audio, API key, private voice ID or local environment file is included.

Project-authored scripts/compositions retain the root MIT license. Font licenses are in each public/fonts directory. Provider-generated media and Remotion usage retain their own terms.
