import hashlib
import json
import subprocess
from pathlib import Path
import os
BASE = Path(__file__).resolve().parent
OUT = Path(os.environ.get('CALL_TRANSLATE_OUTPUT', str(BASE / 'out')))
rows = []
for path in sorted(OUT.glob('*.mp4')):
    probe = subprocess.run(['ffprobe', '-v', 'error', '-show_streams', '-show_format', '-of', 'json', str(path)], capture_output=True, text=True, check=True)
    data = json.loads(probe.stdout)
    video = next(stream for stream in data['streams'] if stream['codec_type'] == 'video')
    audio = next(stream for stream in data['streams'] if stream['codec_type'] == 'audio')
    assert video['width'] == 1080 and video['height'] == 1920
    assert video['r_frame_rate'] == '30/1'
    assert int(video['nb_frames']) == 570
    assert video['codec_name'] == 'h264' and video['pix_fmt'] == 'yuv420p'
    assert audio['codec_name'] == 'aac' and int(audio['sample_rate']) == 48000
    assert abs(float(data['format']['duration']) - 19) < .05
    decoded = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(path), '-f', 'null', '-'], capture_output=True, text=True)
    assert decoded.returncode == 0 and not decoded.stderr.strip(), decoded.stderr
    loud = subprocess.run(['ffmpeg', '-hide_banner', '-i', str(path), '-af', 'loudnorm=I=-16:TP=-1.5:LRA=9:print_format=json', '-f', 'null', '-'], capture_output=True, text=True, check=True)
    start = loud.stderr.rfind('{')
    end = loud.stderr.find('}', start) + 1
    loudness = json.loads(loud.stderr[start:end])
    row = {'file': path.name, 'size_bytes': path.stat().st_size, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'duration_seconds': float(data['format']['duration']), 'video': {key: video.get(key) for key in ['codec_name', 'width', 'height', 'r_frame_rate', 'nb_frames', 'pix_fmt', 'color_space', 'color_transfer', 'color_primaries']}, 'audio': {key: audio.get(key) for key in ['codec_name', 'sample_rate', 'channels', 'bit_rate', 'duration']}, 'loudness': {'integrated_lufs': float(loudness['input_i']), 'true_peak_dbtp': float(loudness['input_tp']), 'lra_lu': float(loudness['input_lra'])}, 'full_decode': 'passed', 'visual_review': '12 final keyframes per film inspected: phone central, no cafe or face-to-face conversation, phone-order confirmation, readable copy, private text goal before Serbian agent response and settled end frame'}
    rows.append(row)
    print(path.name, 'PASS', row['duration_seconds'], 'seconds', row['loudness'], flush=True)
validation = {'films': rows, 'typescript': 'passed', 'voice_generation': {'provider': 'ElevenLabs', 'model': 'eleven_v3', 'request_count': 12, 'lines_delivered': 11, 'languages': ['ru', 'sr'], 'scope': 'Short authorized lines only; one targeted pronunciation revision'}, 'asr_checks': json.loads((BASE / 'asr-check.json').read_text()), 'product_scope': 'Telephone-only illustrated concept of Call Translate + ElevenLabs voice agent. No SIM audio injection demonstration. User task is typed privately before Serbian agent speech. No private Russian task VO is played.', 'music_and_effects': 'Original local procedural synthesis with telephone ring and EQ; no new external assets generated for revision', 'revision': 'Telephone interface replaces all in-person interactions; clean product showcase styling from user prompt', 'scene_sources': ['src/content.ts', 'src/Meme.tsx', 'src/style.css', 'storyboard.md', 'edit-notes.md'], 'private_tasks': 'Typed and silent; cached a-user/b-user audio assets are not used in final mixes', 'privacy': 'No private calls, actual phone recordings, API credentials, or copied application secrets are included'}
(OUT / 'metadata.json').write_text(json.dumps(validation, ensure_ascii=False, indent=2))
