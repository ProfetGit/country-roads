#!/usr/bin/env python3
"""Compose the Country Roads icon from the Blockbench renders in dev/icon/frames/ (pack-icon-animation skill, 60 fps).

Outputs (dev/icon/out/, since ./gradlew may clear dist/):
  icon-animated.webp  60 fps lossless WebP, 256 px, <= 256 KiB (no GIF)
  icon-512.png        still (STILL_FRAME)
  jar-icon-128.png    the mod's own icon, copied to src/main/resources/assets/countryroads/icon.png
  contact.png         frame sheet for review
Background: flat apricot dusk on a 64x64 pixel grid (scaled x8) with a stepped ground shadow under the slab; written to
dev/icon/sprites/bg_flat.png so the banner and kit scripts read the same colours.
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
ICON = ROOT / "dev" / "icon"
OUT = ICON / "out"
MOD_ICON = ROOT / "src/main/resources/assets/countryroads/icon.png"
S = 512
FPS = 60
STILL_FRAME = 90
ROTATE = 90      # the animated WebP starts on the finished scene (first frame = what a static preview shows); the loop is seamless
WEBP_SIZE = 256
WEBP_LIMIT = 256 * 1024
CROP_PCT = 0.90
CROP_MARGIN = 1.10
OUTLINE = 19
INK = (0x2E, 0x1A, 0x10)
APRICOT = (0xE0, 0x8C, 0x52)
SHADOW = (0xC2, 0x70, 0x3E)
TWINKLE = ((0xF2, 0xA8, 0x6E), (0xEC, 0x9A, 0x60))
SHADOW_ELLIPSE = None   # (cx, cy, rx, ry) in 64-grid cells, set after looking at the crop; None = derived from the still


def durations(n: int) -> list[int]:
    return [round((i + 1) * 1000 / FPS) - round(i * 1000 / FPS) for i in range(n)]


def crop_box(raw):
    boxes = [im.getchannel("A").getbbox() for im in raw]
    lo = lambda vals: sorted(vals)[int(len(vals) * (1 - CROP_PCT))]
    hi = lambda vals: sorted(vals)[int(len(vals) * CROP_PCT) - 1]
    box = (lo([b[0] for b in boxes]), lo([b[1] for b in boxes]), hi([b[2] for b in boxes]), hi([b[3] for b in boxes]))
    side = int(max(box[2] - box[0], box[3] - box[1]) * CROP_MARGIN)
    cx, cy = (box[0] + box[2]) // 2, (box[1] + box[3]) // 2
    return (cx - side // 2, cy - side // 2, cx - side // 2 + side, cy - side // 2 + side), box


def background(ellipse) -> Image.Image:
    g = Image.new("RGB", (64, 64), APRICOT)
    d = ImageDraw.Draw(g)
    if ellipse:
        cx, cy, rx, ry = ellipse
        for y in range(64):
            for x in range(64):
                if ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2 <= 1:
                    g.putpixel((x, y), SHADOW)
    for i, (x, y) in enumerate(((6, 8), (54, 12), (10, 54), (57, 50), (30, 4))):
        g.putpixel((x, y), TWINKLE[i % 2])
        if i % 2 == 0:
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                g.putpixel((x + dx, y + dy), TWINKLE[1])
    g.save(ICON / "sprites" / "bg_flat.png")
    return g.convert("RGBA").resize((S, S), Image.NEAREST)


def compose(art: Image.Image, bg: Image.Image) -> Image.Image:
    outline_layer = Image.new("RGBA", (S, S), INK + (0,))
    outline_layer.putalpha(art.getchannel("A").filter(ImageFilter.MaxFilter(OUTLINE)))
    return Image.alpha_composite(Image.alpha_composite(bg, outline_layer), art)


def main() -> None:
    files = sorted((ICON / "frames").glob("frame_*.png"))
    if not files:
        sys.exit("no frames in dev/icon/frames - render them from Blockbench first")
    raw = [Image.open(f).convert("RGBA") for f in files]
    crop, _ = crop_box(raw)
    arts = [im.crop(crop).resize((S, S), Image.NEAREST) for im in raw]

    ellipse = SHADOW_ELLIPSE
    if ellipse is None:
        bb = arts[STILL_FRAME].getchannel("A").getbbox()
        # the slab's lowest corner is the front-most block corner: centre the shadow under the middle of the art, a little above the bottom
        ellipse = ((bb[0] + bb[2]) / 2 / 8, (bb[3] - 22) / 8, (bb[2] - bb[0]) / 8 * 0.46, 3.2)
        print("shadow ellipse (cells):", tuple(round(v, 1) for v in ellipse))
    bg = background(ellipse)
    frames = [compose(a, bg) for a in arts]

    OUT.mkdir(exist_ok=True)
    still = frames[STILL_FRAME].convert("RGB")
    still.save(OUT / "icon-512.png", optimize=True)
    jar = still.resize((128, 128), Image.LANCZOS)
    jar.save(OUT / "jar-icon-128.png", optimize=True)
    MOD_ICON.parent.mkdir(parents=True, exist_ok=True)
    jar.save(MOD_ICON, optimize=True)

    anim = frames[ROTATE:] + frames[:ROTATE]
    small = [f.convert("RGB").resize((WEBP_SIZE, WEBP_SIZE), Image.NEAREST) for f in anim]
    out = OUT / "icon-animated.webp"
    small[0].save(out, save_all=True, append_images=small[1:], duration=durations(len(small)), loop=0,
                  lossless=True, quality=100, method=6)

    cols = 12
    rows = (len(frames) + cols - 1) // cols
    contact = Image.new("RGB", (cols * 96, rows * 96))
    for i, f in enumerate(anim):
        contact.paste(f.convert("RGB").resize((96, 96), Image.LANCZOS), ((i % cols) * 96, (i // cols) * 96))
    contact.save(ICON / "contact.png")

    size = out.stat().st_size
    print(f"crop {crop}; {len(frames)} frames @ {FPS} fps -> {out.relative_to(ROOT)} {size / 1024:.0f} KiB "
          f"({'OK' if size <= WEBP_LIMIT else 'OVER'} 256 KiB icon budget); still = frame {STILL_FRAME}")
    if size > WEBP_LIMIT:
        sys.exit(1)


if __name__ == "__main__":
    main()
