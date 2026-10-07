import json
import os
import subprocess
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = Path(os.environ.get("PROMO_OUTPUT", ROOT / "out"))
REPORT = {"validated": [], "visual_review": "Contact sheets and selected full resolution frames", "limitations": "Illustrated interface. Voice agent is a concept; generated speech does not demonstrate a live SIM conversation."}
for slug in ("translation-promo", "voice-agent-concept", "incoming-inheritance"):
    video = OUTPUT / f"{slug}.mp4"
    if not video.exists():
        continue
    probe = json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-show_format", "-show_streams", "-of", "json", str(video)]))
    stream = next(item for item in probe["streams"] if item["codec_type"] == "video")
    audio_stream = next(item for item in probe["streams"] if item["codec_type"] == "audio")
    assert (stream["width"], stream["height"]) == (1080, 1920)
    assert stream["avg_frame_rate"] == "30/1"
    expected_frames = 600 if slug == "incoming-inheritance" else 540
    assert int(stream["nb_frames"]) == expected_frames
    assert stream["codec_name"] == "h264"
    assert audio_stream["codec_name"] == "aac"
    assert audio_stream["channels"] == 2
    subprocess.run(["ffmpeg", "-v", "error", "-i", str(video), "-f", "null", "-"], check=True)
    pcm = np.frombuffer(subprocess.check_output(["ffmpeg", "-v", "error", "-i", str(video), "-vn", "-f", "f32le", "-ac", "2", "-ar", "48000", "-"]), dtype="<f4")
    peak = float(np.max(np.abs(pcm)))
    clipped = int(np.count_nonzero(np.abs(pcm) >= 0.999))
    assert clipped == 0
    result = subprocess.run(["ffmpeg", "-hide_banner", "-i", str(video), "-af", "loudnorm=I=-16:TP=-1.2:LRA=8:print_format=json", "-f", "null", "-"], capture_output=True, text=True, check=True)
    loudness, _ = json.JSONDecoder().raw_decode(result.stderr[result.stderr.rfind("{"):])
    frames = sorted((OUTPUT / f"{slug}-frames").glob("*.png"))
    sheet = Image.new("RGB", (1080, 1032), "#FFF9ED")
    draw = ImageDraw.Draw(sheet)
    for index, frame in enumerate(frames):
        image = Image.open(frame).convert("RGB")
        assert image.size == (1080, 1920)
        assert float(np.asarray(image).std()) > 20
        image.thumbnail((270, 480), Image.Resampling.LANCZOS)
        x, y = (index % 4) * 270, (index // 4) * 516
        sheet.paste(image, (x, y))
        label = f"{int(frame.stem) / 30:.2f}s / frame {frame.stem}"
        draw.text((x + 8, y + 490), label, fill="#15233F")
    sheet.save(OUTPUT / f"{slug}-contact-sheet.jpg", quality=95)
    entry = {"file": video.name, "video": {"width": stream["width"], "height": stream["height"], "fps": 30, "frames": expected_frames, "duration_s": float(stream["duration"]), "codec": stream["codec_name"], "pixel_format": stream["pix_fmt"], "color_space": stream.get("color_space")}, "audio": {"codec": audio_stream["codec_name"], "sample_rate": audio_stream["sample_rate"], "channels": audio_stream["channels"], "sample_peak": round(peak, 5), "clipped_samples": clipped, "integrated_lufs": float(loudness["input_i"]), "true_peak_dbtp": float(loudness["input_tp"]), "loudness_range_lu": float(loudness["input_lra"])}, "container_duration_s": float(probe["format"]["duration"]), "size_bytes": int(probe["format"]["size"]), "decode": "passed"}
    REPORT["validated"].append(entry)
    print(json.dumps(entry, ensure_ascii=False), flush=True)
(OUTPUT / "validation.json").write_text(json.dumps(REPORT, ensure_ascii=False, indent=2) + "\n")
