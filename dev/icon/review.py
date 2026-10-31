#!/usr/bin/env python3
"""Texture review sheet for the Country Roads icon: every sprite at 10x plus an isometric mockup of the road scene.
Python approximation of the Blockbench camera (west face left, south face right)."""
import importlib.util, sys
from pathlib import Path
from PIL import Image, ImageDraw

spec = importlib.util.spec_from_file_location("tr", "/home/emppu/Projects/Minecraft Datapacks/.claude/skills/pack-icon-animation/assets/texture_review.py")
tr = importlib.util.module_from_spec(spec); spec.loader.exec_module(tr)

HERE = Path(__file__).parent
S = HERE / "sprites"
sp = {p.stem: Image.open(p).convert("RGBA") for p in sorted(S.glob("*.png")) if not p.name.startswith(("bg_", "banner_", "review"))}

# (cx, cz, kind)
LAYOUTS = {
    "B": [(0, 1, "road"), (1, 1, "road"), (1, 0, "road"), (2, 0, "road"), (3, 0, "road"), (0, 0, "grass"), (2, 1, "grass"), (3, 1, "grass"), (0, 2, "grass")],
}
SIGN_AT, FLOWERS = (0, 1), (2, 1), [((0, 0), "flower_red", 0.2, 0.3), ((3, 1), "flower_yellow", 0.3, 0.6), ((0, 2), "flower_blue", 0.6, 0.4), ((2, 1), "flower_red", 0.7, 0.75)]
BG = (196, 118, 62, 255)


def scene(layout, size=(640, 520), scale=104, bg=BG, items=False):
    im = Image.new("RGBA", size, bg)
    off = (size[0] // 2 - 20, int(size[1] * 0.62))
    order = sorted(layout, key=lambda b: tr.proj((b[0] + .5, .5, b[1] + .5), 1)[2])
    for cx, cz, kind in order:
        x, y, z = cx - 0.5, 0, cz - 0.5
        side = "road_side" if kind == "road" else "grass_side"
        top = "road_top" if kind == "road" else "grass_top"
        tr.face(im, sp[side + "_left"], [(x, y + 1, z), (x, y + 1, z + 1), (x, y, z)], scale, off)
        tr.face(im, sp[side + "_right"], [(x, y + 1, z + 1), (x + 1, y + 1, z + 1), (x, y, z + 1)], scale, off)
        tr.face(im, sp[top], [(x, y + 1, z), (x + 1, y + 1, z), (x, y + 1, z + 1)], scale, off)
    if items:
        def at(cx, cz, fx=0.5, fz=0.5, h=1.0):
            p = tr.proj((cx - 0.5 + fx, h, cz - 0.5 + fz), scale)
            return off[0] + p[0], off[1] + p[1]
        k = scale / 16.0 * 1.25
        def put(name, px, ax, ay, kk=None):
            kk = kk or k
            img = sp[name].resize((int(sp[name].width * kk), int(sp[name].height * kk)), Image.NEAREST)
            im.alpha_composite(img, (int(px[0] - ax * kk), int(px[1] - ay * kk)))
        for (c, name, fx, fz) in FLOWERS:
            put(name, at(c[0], c[1], fx, fz), 4, 8, scale / 16.0 * 0.8)
        px = at(*SIGN_AT, 0.35, 0.45); put("signpost_post", px, 2, 22); put("sign_board", (px[0] + 3 * k, px[1] - 17 * k), 0, 3)
    return im


def main(out="review.png", layout="B"):
    z, cols = 10, 6
    cell = 16 * z + 24
    files = list(sp)
    rows = (len(files) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * cell + 660, max(rows * (cell + 18), 560) + 20), (28, 35, 51, 255))
    d = ImageDraw.Draw(sheet)
    for i, name in enumerate(files):
        im = sp[name]
        k = max(1, z * 16 // max(im.width, im.height))
        x, y = (i % cols) * cell + 12, (i // cols) * (cell + 18) + 12
        sheet.alpha_composite(im.resize((im.width * k, im.height * k), Image.NEAREST), (x, y))
        d.text((x, y + 16 * z + 4), name, fill=(220, 224, 235, 255))
    m = scene(LAYOUTS[layout], size=(640, 520), scale=96, items=True)
    sheet.alpha_composite(m, (cols * cell + 10, 10))
    d.text((cols * cell + 20, sheet.height - 24), "mockup (Python approximation, not Blockbench; items roughly seated)", fill=(220, 224, 235, 255))
    sheet.save(out)
    print(out, len(files), "sprites")


if __name__ == "__main__":
    main(*sys.argv[1:])
