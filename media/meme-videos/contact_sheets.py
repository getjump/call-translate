from pathlib import Path
import os
from PIL import Image, ImageDraw, ImageFont
BASE = Path(os.environ.get('CALL_TRANSLATE_OUTPUT', str(Path(__file__).resolve().parent / 'out')))
for folder in sorted(BASE.glob('*-keyframes')):
    frames = sorted(folder.glob('*.png'))
    canvas = Image.new('RGB', (1080, 2136), '#232449')
    draw = ImageDraw.Draw(canvas)
    for i, path in enumerate(frames):
        im = Image.open(path).convert('RGB').resize((270, 480), Image.Resampling.LANCZOS)
        x = (i % 4) * 270
        y = (i // 4) * 534
        canvas.paste(im, (x, y))
        draw.text((x + 12, y + 488), 'Frame ' + path.stem + ' / ' + str(round(int(path.stem) / 30, 2)) + 's', fill='#F4F7F1')
    canvas = canvas.crop((0, 0, 1080, 1602))
    canvas.save(BASE / (folder.name.replace('-keyframes', '') + '-contact-sheet.jpg'), quality=95)
