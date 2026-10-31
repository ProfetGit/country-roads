#!/usr/bin/env python3
"""Compose the Modrinth description banner (3:1) from Blockbench renders in dev/icon/banner_frames/.

Outputs (dev/icon/out/): banner.png (static, STILL_FRAME) and banner-animated.webp (full loop, 60 fps lossless WebP with alpha corners, no GIF).
Art: dev/icon/build_scene.js rendered with ROADS.camera(0.18) -> banner_frames/ (1600px, 1:1, no resampling).
Background, title and tagline: dev/icon/sprites/banner_*.aseprite, scaled up nearest-neighbour."""
import sys
from pathlib import Path

from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
ICON = ROOT / "dev" / "icon"
SPRITES = ICON / "sprites"
DIST = ICON / "out"
W, H = 1536, 512
FPS = 60  # banner_frames must be rendered at 60 fps
STILL_FRAME = 90
ROTATE = 90      # animated WebP starts on the finished scene
ART_CENTER = (1150, 262)  # where the robust centre of the art lands in the banner
OUTLINE = 17
INK = (0x2E, 0x1A, 0x10)
LAYERS = (("banner_bg", 8, (0, 0)), ("banner_title_top", 6, (74, 100)), ("banner_title", 10, (72, 192)), ("banner_tagline", 6, (74, 356)))
WEBP_LIMIT = 5 * 1024 * 1024
GRID = 8
CORNER = (4, 2, 1, 1)  # pixel-art rounded corners: cells cut from the edge per row, on the 8px grid; symmetric, so rows = columns


def durations(n: int) -> list[int]:
    """Per-frame ms that average exactly 1000/FPS (16.67 ms at 60 fps); WebP stores whole milliseconds."""
    return [round((i + 1) * 1000 / FPS) - round(i * 1000 / FPS) for i in range(n)]


def sprite(name: str, scale: int) -> Image.Image:
    im = Image.open(SPRITES / f"{name}.png").convert("RGBA")
    return im.resize((im.width * scale, im.height * scale), Image.NEAREST)


def art_offset(files) -> tuple[int, int]:
    """banner = frame + offset, from the robust (10-90%) bounding box of all frames."""
    boxes = [Image.open(f).convert("RGBA").getchannel("A").getbbox() for f in files]
    mid = lambda vals: sorted(vals)[len(vals) // 2]
    cx = (mid([b[0] for b in boxes]) + mid([b[2] for b in boxes])) / 2
    cy = (mid([b[1] for b in boxes]) + mid([b[3] for b in boxes])) / 2
    return round(ART_CENTER[0] - cx), round(ART_CENTER[1] - cy)


def art(frame: Path, offset: tuple[int, int]) -> Image.Image:
    im = Image.open(frame).convert("RGBA")
    dx, dy = offset
    return im.crop((-dx, -dy, W - dx, H - dy))


def corner_mask() -> Image.Image:
    """255 inside the banner, 0 in the stepped corners."""
    gw, gh = W // GRID, H // GRID
    m = Image.new("L", (gw, gh), 255)
    for row, cut in enumerate(CORNER):
        for x in range(cut):
            for p in ((x, row), (gw - 1 - x, row), (x, gh - 1 - row), (gw - 1 - x, gh - 1 - row)):
                m.putpixel(p, 0)
    return m.resize((W, H), Image.NEAREST)


def compose(a: Image.Image, bg: Image.Image, text: list[tuple[Image.Image, tuple[int, int]]]) -> Image.Image:
    out = bg.copy()
    outline = Image.new("RGBA", (W, H), INK + (0,))
    outline.putalpha(a.getchannel("A").filter(ImageFilter.MaxFilter(OUTLINE)))
    out.alpha_composite(outline)
    out.alpha_composite(a)
    for im, pos in text:
        out.alpha_composite(im, pos)
    return out


def main() -> None:
    files = sorted((ICON / "banner_frames").glob("frame_*.png"))
    if not files:
        sys.exit("no frames in dev/icon/banner_frames - render them from Blockbench first (ROADS.camera(0.18))")
    (bg_name, bg_scale, _), *text_layers = LAYERS
    bg = sprite(bg_name, bg_scale)
    text = [(sprite(name, scale), pos) for name, scale, pos in text_layers]
    offset = art_offset(files)
    print("art offset", offset)
    frames = [compose(art(f, offset), bg, text).convert("RGB") for f in files]

    DIST.mkdir(exist_ok=True)
    mask = corner_mask()
    still = frames[STILL_FRAME].convert("RGBA")
    still.putalpha(mask)
    still.save(DIST / "banner.png", optimize=True)

    out = DIST / "banner-animated.webp"
    rgba = []
    for f in frames[ROTATE:] + frames[:ROTATE]:
        f = f.convert("RGBA")
        f.putalpha(mask)
        rgba.append(f)
    rgba[0].save(out, save_all=True, append_images=rgba[1:], duration=durations(len(rgba)), loop=0,
                 lossless=True, quality=100, method=6, exact=True)

    size = out.stat().st_size
    print(f"dist/banner.png (frame {STILL_FRAME}); {len(frames)} frames -> {out.relative_to(ROOT)} {size / 1024:.0f} KiB "
          f"({'OK' if size <= WEBP_LIMIT else 'OVER'} Modrinth 5 MiB gallery limit)")
    if size > WEBP_LIMIT:
        sys.exit(1)


if __name__ == "__main__":
    main()
