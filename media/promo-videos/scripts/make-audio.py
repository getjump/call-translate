import json
import subprocess
import wave
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "public"
RATE = 48000
DURATION = 18
N = RATE * DURATION
RNG = np.random.default_rng(42)

def note(midi):
    return 440 * 2 ** ((midi - 69) / 12)

def place(track, signal, at, gain=1, pan=0):
    offset = int(at * RATE)
    end = min(offset + len(signal), len(track))
    if end <= offset:
        return
    signal = signal[:end - offset] * gain
    if signal.ndim == 1:
        track[offset:end, 0] += signal * (1 - max(0, pan) * 0.45)
        track[offset:end, 1] += signal * (1 + min(0, pan) * 0.45)
    else:
        track[offset:end] += signal

def tone(freq, length, kind="pluck"):
    t = np.arange(int(length * RATE)) / RATE
    attack = np.minimum(t / 0.006, 1)
    if kind == "bass":
        env = attack * np.exp(-t * 8) * np.minimum((length - t) / 0.03, 1)
        return (np.sin(2 * np.pi * freq * t) + 0.18 * np.sin(4 * np.pi * freq * t)) * env
    env = attack * np.exp(-t * 6)
    return (np.sin(2 * np.pi * freq * t) + 0.27 * np.sin(4 * np.pi * freq * t) + 0.12 * np.sin(6 * np.pi * freq * t)) * env

def build_music(variant):
    track = np.zeros((N, 2), dtype=np.float64)
    beat = 60 / 128
    for i in range(39):
        at = i * beat
        if at >= DURATION:
            break
        t = np.arange(int(0.24 * RATE)) / RATE
        kick = np.sin(2 * np.pi * (47 * t + 95 * (1 - np.exp(-t * 28)) / 28)) * np.exp(-t * 21)
        place(track, kick, at, 0.22 if at > 2.8 else 0.09)
        if i % 2 == 1:
            t = np.arange(int(0.13 * RATE)) / RATE
            snare = (RNG.standard_normal(len(t)) * 0.45 + np.sin(2 * np.pi * 185 * t) * 0.35) * np.exp(-t * 42)
            place(track, snare, at, 0.12)
        for half in (0, 0.5):
            t = np.arange(int(0.048 * RATE)) / RATE
            noise = RNG.standard_normal(len(t))
            hat = np.concatenate([[0], np.diff(noise)]) * np.exp(-t * 92)
            place(track, hat, at + half * beat, 0.016, (-1 if i % 2 else 1) * 0.6)
        bassline = [42, 42, 45, 49, 38, 38, 40, 45]
        place(track, tone(note(bassline[i % 8]), 0.33, "bass"), at + 0.012, 0.18)
        melody = [66, 69, 73, 76, 73, 69, 64, 69] if variant == "translation" else [73, 69, 66, 76, 78, 76, 73, 69]
        if i % 2 == 0 or i > 29:
            pluck = tone(note(melody[(i // 2) % 8]), 0.6)
            place(track, pluck, at + beat * 0.5, 0.067, -0.65)
            place(track, pluck, at + beat * 0.5 + 0.12, 0.021, 0.7)
    for at in ([3.333, 6.167, 12.167, 14.833] if variant == "translation" else [3, 6.333, 12, 14.667]):
        t = np.arange(int(0.30 * RATE)) / RATE
        sweep = np.sin(2 * np.pi * (700 * t + 1400 * t * t)) * np.sin(np.pi * t / 0.30) * np.exp(-t * 4)
        place(track, sweep, max(0, at - 0.16), 0.05)
        place(track, tone(1108, 0.13), at + 0.04, 0.07, 0.25)
    fade = np.minimum(np.arange(N) / RATE / 0.04, 1) * np.clip((DURATION - np.arange(N) / RATE) / 0.55, 0, 1)
    track *= fade[:, None]
    return track

def decode(path):
    data = subprocess.check_output(["ffmpeg", "-v", "error", "-i", str(path), "-f", "f32le", "-ar", str(RATE), "-ac", "1", "-"])
    audio = np.frombuffer(data, dtype="<f4").astype(np.float64)
    audio *= 0.68 / max(0.01, float(np.max(np.abs(audio))))
    return audio

metadata = {"sample_rate": RATE, "duration": DURATION, "music": "Original procedural synth, generated locally", "voices": "ElevenLabs eleven_v3, three requested clips", "timelines": {}}
for mode, clips in [("translation", [("caller-serbian", 1.45)]), ("agent", [("task-russian", 3.35), ("agent-serbian", 6.85)])]:
    track = build_music(mode)
    timeline = []
    for name, at in clips:
        voice = decode(OUT / "voice" / f"{name}.mp3")
        start = max(0, int((at - 0.12) * RATE))
        end = min(N, int((at + len(voice) / RATE + 0.18) * RATE))
        duck = np.ones(end - start)
        ramp = min(int(0.12 * RATE), len(duck) // 2)
        duck[:ramp] = np.linspace(1, 0.24, ramp)
        duck[ramp:-ramp] = 0.24
        duck[-ramp:] = np.linspace(0.24, 1, ramp)
        track[start:end] *= duck[:, None]
        place(track, voice, at)
        timeline.append({"clip": name, "at": at, "duration": round(len(voice) / RATE, 3)})
    if mode == "translation":
        for at in (0.22, 0.62):
            t = np.arange(int(0.20 * RATE)) / RATE
            ring = (np.sin(2 * np.pi * 660 * t) + np.sin(2 * np.pi * 880 * t)) * 0.5 * np.sin(np.pi * t / 0.2)
            place(track, ring, at, 0.13)
    peak = float(np.max(np.abs(track)))
    if peak > 0.84:
        track *= 0.84 / peak
    raw = OUT / f"{mode}-premaster.wav"
    with wave.open(str(raw), "wb") as file:
        file.setnchannels(2)
        file.setsampwidth(2)
        file.setframerate(RATE)
        file.writeframes((np.clip(track, -1, 1) * 32767).astype("<i2").tobytes())
    target = OUT / f"{mode}-track.wav"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(raw), "-af", "loudnorm=I=-16:TP=-1.2:LRA=8", "-ar", str(RATE), "-ac", "2", str(target)], check=True)
    raw.unlink()
    metadata["timelines"][mode] = timeline
    print(f"made {target.name}", flush=True)
(ROOT / "audio-manifest.json").write_text(json.dumps(metadata, indent=2, ensure_ascii=False) + "\n")
