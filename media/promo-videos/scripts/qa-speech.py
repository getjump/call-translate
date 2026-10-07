import json
import os
from pathlib import Path

os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["PYTHONDONTWRITEBYTECODE"] = "1"

import mlx_whisper

ROOT = Path(__file__).resolve().parents[1]
OUT = Path(os.environ.get("PROMO_OUTPUT", ROOT / "out"))
OUT.mkdir(exist_ok=True)
clips = [
    ("caller-serbian", "sr", "Dostava stiže za deset minuta. Da li ste kod kuće?"),
    ("task-russian", "ru", "Уточни, когда привезут заказ."),
    ("agent-serbian", "sr", "Dobar dan! Možete li da mi kažete kada stiže moja porudžbina?"),
    ("inheritance-serbian", "sr", "Dobar dan. Zovem iz advokatske kancelarije. Tražimo naslednika pokojnog Milana Petrovića. Ostavio je vinariju i milijardu dinara."),
]
report = []
for name, language, expected in clips:
    result = mlx_whisper.transcribe(str(ROOT / "public" / "voice" / f"{name}.mp3"), path_or_hf_repo="mlx-community/whisper-large-v3-turbo", language=language)
    entry = {"clip": name, "language": language, "expected": expected, "recognized": result["text"].strip()}
    report.append(entry)
    print(json.dumps(entry, ensure_ascii=False), flush=True)
(OUT / "speech-qa.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
