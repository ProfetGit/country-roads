#!/usr/bin/env python3
"""Export the approved roadside props from the Showcase asset library to the mod's prop templates.

  python3 dev/props/export.py          writes src/main/resources/data/countryroads/props/<name>.json

The assets (media/Showcase/assets/library/cr_*.py) are the source of truth; edit them, check them in the asset viewer,
then run this. Template frame: x along the road, +z toward the road (the bench faces away from it: "front": "away"),
y 0 = the floor a player stands on; cells with y < 0 replace ground (churned soil, the stash socket). "@main" in a block
id is the biome's wood (spruce, oak, mangrove, dark_oak, jungle). "loot" marks containers, "entities" the cushions."""
import importlib.util
import json
import sys
from pathlib import Path

ASSETS = Path(__file__).resolve().parents[4] / "media/Showcase/assets"
OUT = Path(__file__).resolve().parents[2] / "src/main/resources/data/countryroads/props"
sys.path.insert(0, str(ASSETS))
from lib.asset import Builder  # noqa: E402
from lib import roadside  # noqa: E402


def load(name):
    spec = importlib.util.spec_from_file_location(name, ASSETS / "library" / f"{name}.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.ASSET


def lost_cart():
    a = load("cr_lost_cart")
    b, _ = a.make(main="@main", stake="@main", wheel="oak", context=False)
    return a, b, {"chest": "countryroads:chests/lost_cart"}, "road"


def milestone():
    b = Builder("milestone")
    roadside.slim_milestone(b, 0, 0, "cairn", seed=0, sign=False)
    return load("cr_milestone_styles"), b, {}, "road"


def milestone_stash():
    a = load("cr_hidden_stash")
    b = Builder("milestone_stash")
    a.stash(b, 0, "found")
    return a, b, {"barrel found": "countryroads:chests/milestone_stash"}, "road"


def wayside_shrine():
    a = load("cr_wayside_shrine")
    b, _ = a.make(wood="@main", context=False)
    return a, b, {}, "road"


def bench():
    a = load("cr_bench")
    b, _ = a.make(wood="@main", cushion="brown", context=False)
    return a, b, {}, "away"


def hay_cart():
    a = load("cr_farm_props")
    b = Builder("hay_cart")
    a.hay_cart(b, 0, "@main", "oak")
    return a, b, {}, "road"


def hay_bales():
    a = load("cr_farm_props")
    b = Builder("hay_bales")
    a.bales(b, 0)
    return a, b, {}, "road"


PROPS = {"lost_cart": lost_cart, "milestone": milestone, "milestone_stash": milestone_stash,
         "wayside_shrine": wayside_shrine, "bench": bench, "hay_cart": hay_cart, "hay_bales": hay_bales}


def entity(cell, state):
    name, _, props = state.partition("[")
    kv = dict(p.split("=") for p in props.rstrip("]").split(",")) if props else {}
    x, y, z = cell
    return {"type": name.split(":")[-1], "x": x, "y": y + float(kv.pop("y", 0)) / 16, "z": z, **kv}


def export(name, fn):
    asset, b, loot_marks, front = fn()
    cells = {c: s for c, s in b.cells.items() if b.who[c] != "context"}
    blocks = {c: s for c, s in cells.items() if not s.startswith("entity:")}
    palette = sorted(set(blocks.values()))
    idx = {s: i for i, s in enumerate(palette)}
    order = sorted(blocks, key=lambda c: (c[1], c[0], c[2]))
    loot = []
    for mark, table in loot_marks.items():
        x, y, z = b.marks[mark]
        loot.append({"x": x, "y": y, "z": z, "table": table})
    out = {
        "name": name, "asset": asset.name, "version": asset.version, "front": front,
        "palette": palette,
        "blocks": [[x, y, z, idx[blocks[(x, y, z)]]] for (x, y, z) in order],
        "loot": loot,
        "entities": [entity(c, s[len("entity:"):]) for c, s in sorted(cells.items()) if s.startswith("entity:")],
    }
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{name}.json").write_text(json.dumps(out, separators=(",", ":")) + "\n")
    xs = [c[0] for c in blocks]; zs = [c[2] for c in blocks]; ys = [c[1] for c in blocks]
    print(f"{name}: {len(blocks)} blocks, {len(out['entities'])} entities, {len(loot)} loot, "
          f"x {min(xs)}..{max(xs)} z {min(zs)}..{max(zs)} y {min(ys)}..{max(ys)} (asset {asset.name} {asset.version})")


if __name__ == "__main__":
    for n, f in PROPS.items():
        export(n, f)
