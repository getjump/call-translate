import json
import subprocess
import wave
from pathlib import Path
import numpy as np

BASE = Path(__file__).resolve().parent
OUT = BASE / 'public' / 'audio'
SR = 48000
DURATION = 19
RNG = np.random.default_rng(2718)

def read_audio(path, limit, phone=False):
    probe = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'json', str(path)], capture_output=True, text=True, check=True)
    original = float(json.loads(probe.stdout)['format']['duration'])
    tempo = max(1, original / limit)
    filter_chain = 'silenceremove=start_periods=1:start_duration=0.02:start_threshold=-40dB,atempo=' + str(min(2, tempo))
    if phone:
        filter_chain += ',highpass=f=220,lowpass=f=3800'
    if tempo > 2:
        filter_chain += ',atempo=' + str(tempo / 2)
    result = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(path), '-af', filter_chain, '-ar', str(SR), '-ac', '1', '-f', 'f32le', '-'], capture_output=True, check=True)
    return np.frombuffer(result.stdout, dtype=np.float32).copy(), original, tempo

def add(master, clip, time, volume=1):
    start = int(time * SR)
    length = min(len(clip), len(master) - start)
    if length > 0:
        master[start:start + length] += clip[:length] * volume

def tone(frequency, duration, mode='pluck'):
    t = np.arange(int(duration * SR)) / SR
    if mode == 'bass':
        return (np.sin(2 * np.pi * frequency * t) + .3 * np.sin(4 * np.pi * frequency * t)) * np.exp(-t * 9) * .13
    if mode == 'bell':
        return (np.sin(2 * np.pi * frequency * t) + .35 * np.sin(2 * np.pi * frequency * 2.76 * t)) * np.exp(-t * 7) * .085
    return np.sin(2 * np.pi * frequency * t) * np.exp(-t * 17) * .075

def whoosh():
    t = np.arange(int(.23 * SR)) / SR
    return RNG.normal(0, 1, len(t)) * np.sin(np.pi * t / .23) ** 2 * .028

def boing():
    t = np.arange(int(.75 * SR)) / SR
    phase = np.cumsum((130 + 480 * np.exp(-t * 13) + 18 * np.sin(t * 35)) / SR) * 2 * np.pi
    return np.sin(phase) * np.exp(-t * 6) * .22

def ring():
    t = np.arange(int(.7 * SR)) / SR
    envelope = np.sin(np.pi * np.minimum(t, .13) / .26) ** 2 * np.minimum(1, (.7 - t) / .12)
    return (np.sin(2 * np.pi * 440 * t) + np.sin(2 * np.pi * 480 * t)) * .065 * envelope

def scratch():
    t = np.arange(int(.55 * SR)) / SR
    return (.5 * np.sin(2 * np.pi * (590 * t - 410 * t * t)) + .2 * RNG.normal(0, 1, len(t))) * np.sin(np.pi * t / .55) ** 2 * .23

schedules = {
    'a': [('a-hook', .20, 2.15), ('hero-vodu', 2.46, .69), ('hero-vodu', 3.40, .69), ('hero-vodu', 4.33, .69), ('a-panic', 5.38, 1.02), ('hero-pivo', 7.26, 1.08), ('a-payoff', 9.04, 1.48), ('agent-vodu', 14.17, 1.18)],
    'b': [('b-hook', .20, 1.65), ('hero-vodu', 2.25, .60), ('hero-vodu', 3.05, .60), ('hero-vodu', 3.85, .60), ('hero-vodu', 4.77, 1.12), ('b-payoff', 8.73, 1.56), ('agent-pivo', 12.39, 1.2)]
}
all_report = {}
for variant, schedule in schedules.items():
    music = np.zeros(DURATION * SR)
    melody = [523.25, 659.25, 783.99, 659.25, 587.33, 698.46, 783.99, 698.46]
    beat = .48
    for i in range(int(DURATION / beat)):
        time = i * beat
        add(music, tone([130.81, 146.83, 164.81, 146.83][(i // 8) % 4], .35, 'bass'), time)
        add(music, tone(melody[i % 8], .30), time + .24)
        noise = RNG.normal(0, 1, int(.055 * SR)) * np.exp(-np.arange(int(.055 * SR)) / SR * 75) * .013
        add(music, noise, time)
    voice = np.zeros_like(music)
    report = []
    for name, time, limit in schedule:
        path = OUT / (name + '.mp3')
        if not path.exists():
            continue
        clip, original, tempo = read_audio(path, limit, phone=name.startswith('agent-') or (name.startswith('hero-') and time > 4.5))
        peak = np.max(np.abs(clip))
        if peak:
            clip *= .63 / peak
        add(voice, clip, time)
        start = max(0, int((time - .08) * SR))
        end = min(len(music), int((time + len(clip) / SR + .12) * SR))
        music[start:end] *= .25
        report.append({'id': name, 'start': time, 'end': round(time + len(clip) / SR, 3), 'original_duration': round(original, 3), 'tempo': round(tempo, 3)})
    fx = np.zeros_like(music)
    cuts = [2.4, 3.33, 4.27, 5.2, 6.6, 7.2, 10.8, 12.4, 15.9] if variant == 'a' else [2.2, 3, 3.8, 4.6, 8.6, 10.6, 14.6]
    for time in cuts:
        add(fx, whoosh(), time, 1.3)
    add(fx, ring(), 5.21 if variant == 'a' else 4.61)
    for i in range(7):
        add(fx, tone(650 + i * 49, .055), (12.64 if variant == 'a' else 10.84) + i * .08, .9)
    reveal = 8.6 if variant == 'a' else 6.2
    add(fx, scratch(), reveal)
    add(fx, boing(), reveal + .20, .9)
    music[int(reveal * SR):int((reveal + .7) * SR)] *= .05
    confirm = 13.9 if variant == 'a' else 12.1
    for i, note in enumerate([783.99, 987.77, 1174.66]):
        add(fx, tone(note, .42, 'bell'), confirm + i * .13, 1.3)
    delivery = 15.9 if variant == 'a' else 14.6
    for i, note in enumerate([523.25, 659.25, 783.99, 1046.5]):
        add(fx, tone(note, .65, 'bell'), delivery + i * .095, 1.3)
    mix = music + voice + fx
    fade = np.ones_like(mix)
    fade[-int(.55 * SR):] = np.linspace(1, 0, int(.55 * SR))
    mix *= fade
    mix = np.tanh(mix * 1.1) * .83
    stereo = np.column_stack([mix, mix])
    with wave.open(str(OUT / ('mix-' + variant + '.wav')), 'wb') as wav:
        wav.setnchannels(2)
        wav.setsampwidth(2)
        wav.setframerate(SR)
        wav.writeframes((stereo * 32767).astype('<i2').tobytes())
    normalized = OUT / ('mix-' + variant + '-master.wav')
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(OUT / ('mix-' + variant + '.wav')), '-af', 'loudnorm=I=-16:TP=-1.5:LRA=9', '-ar', str(SR), '-c:a', 'pcm_s16le', str(normalized)], check=True)
    normalized.replace(OUT / ('mix-' + variant + '.wav'))
    all_report[variant] = {'duration': DURATION, 'sample_rate': SR, 'voices': report, 'peak_before_mastering': round(float(np.max(np.abs(mix))), 4), 'mastering': 'loudnorm I=-16 TP=-1.5 LRA=9', 'music': 'Original procedural melody and percussion', 'sfx': 'Original local synthesis: whoosh, scratch, boing, confirmation'}
(BASE / 'audio-timeline.json').write_text(json.dumps(all_report, ensure_ascii=False, indent=2))
print('Audio timelines saved; mixes created locally.')
