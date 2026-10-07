import json
import os
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENV_SOURCE = Path(os.environ["PROMO_ELEVEN_ENV"]) if os.environ.get("PROMO_ELEVEN_ENV") else None
VALUES = {}
for line in ENV_SOURCE.read_text().splitlines() if ENV_SOURCE and ENV_SOURCE.exists() else []:
    line = line.strip()
    if line and not line.startswith("#") and "=" in line:
        name, value = line.split("=", 1)
        VALUES[name.strip().removeprefix("export ").strip()] = value.strip().strip('"').strip("'")
KEY = os.environ.get("ELEVENLABS_API_KEY") or VALUES.get("ELEVENLABS_API_KEY")
CLIPS = [
    ("caller-serbian", "nPczCjzI2devNBz1zQrb", "Dostava stiže za deset minuta. Da li ste kod kuće?"),
    ("task-russian", "pFZP5JQG7iQjIQuC4Bku", "Уточни, когда привезут заказ."),
    ("agent-serbian", "pFZP5JQG7iQjIQuC4Bku", "Dobar dan! Možete li da mi kažete kada stiže moja porudžbina?"),
    ("inheritance-serbian", "nPczCjzI2devNBz1zQrb", "Добар дан. Зовем из адвокатске канцеларије. Тражимо наследника покојног Милана Петровића. Оставио је винарију и милијарду динара."),
]
OUT = ROOT / "public" / "voice"
OUT.mkdir(exist_ok=True)
for name, voice, phrase in CLIPS:
    target = OUT / f"{name}.mp3"
    if target.exists():
        print(f"cached {name}", flush=True)
        continue
    if not KEY:
        raise SystemExit("ELEVENLABS_API_KEY is required for missing voice clips")
    payload = {"text": phrase, "model_id": "eleven_v3", "voice_settings": {"stability": 0.5}}
    if name == "inheritance-serbian":
        payload["language_code"] = "sr"
    body = json.dumps(payload).encode()
    request = urllib.request.Request(
        f"https://api.elevenlabs.io/v1/text-to-speech/{voice}?output_format=mp3_44100_128",
        data=body,
        headers={"xi-api-key": KEY, "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            audio = response.read()
    except Exception as error:
        raise SystemExit(f"voice generation failed for {name}: {type(error).__name__}")
    if len(audio) < 1000:
        raise SystemExit(f"empty voice output for {name}")
    target.write_bytes(audio)
    print(f"generated {name}: {len(audio)} bytes", flush=True)
