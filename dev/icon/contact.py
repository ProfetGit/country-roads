#!/usr/bin/env python3
"""Contact sheet of rendered frames: contact.py <dir> <out.png> <frame numbers...>  (apricot background, cropped square around the art)"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw
d, out, nums = Path(sys.argv[1]), sys.argv[2], [int(n) for n in sys.argv[3:]]
BG = (224, 140, 82, 255)
cell = 300
cols = 4
rows = (len(nums) + cols - 1) // cols
sheet = Image.new("RGBA", (cols * cell, rows * cell), BG)
dr = ImageDraw.Draw(sheet)
for i, n in enumerate(nums):
    im = Image.open(d / f"frame_{n:03d}.png").convert("RGBA").crop((120, 200, 1480, 1560))
    t = Image.new("RGBA", im.size, BG); t.alpha_composite(im)
    sheet.paste(t.resize((cell, cell), Image.LANCZOS), ((i % cols) * cell, (i // cols) * cell))
    dr.text(((i % cols) * cell + 6, (i // cols) * cell + 4), f"f{n} t={n/60:.2f}", fill=(0, 0, 0, 255))
sheet.save(out)
