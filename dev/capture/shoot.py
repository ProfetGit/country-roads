#!/usr/bin/env python3
"""Client stills of generated roads: pregenerate around a point on a seed (server), pick camera shots along the roads
(countryroads shots), then play the world in the real client off-screen and keep one frame per shot.

shoot.py --seed 12345 --at -120 1916 [--radius 300] [--name look] [--mods x.jar ...]
Output: dev/capture/out/<name>/<label>.png and sheet.png. Uses tools/ClientCapture (client slot) and dev/server/wg.py.
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
WS = ROOT.parents[1]
SEG = 100


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", default="12345")
    ap.add_argument("--at", nargs=2, type=int, required=True)
    ap.add_argument("--radius", type=int, default=300)
    ap.add_argument("--name", default="look")
    ap.add_argument("--mods", nargs="*", default=[])
    ap.add_argument("--time", type=int, default=5000)
    ap.add_argument("--jvm", action="append", default=[], help="extra server JVM arg (wg.py --jvm)")
    ap.add_argument("--shot", action="append", default=[], help="own camera 'x y z yaw pitch label' (repeatable)")
    ap.add_argument("--only", action="store_true", help="only the --shot cameras, not the road's own picks")
    ap.add_argument("--port", type=int, default=25591)
    ap.add_argument("--after", nargs="*", default=[], help="extra commands after pregen (e.g. 'countryroads staketest X Z keep')")
    ap.add_argument("--only-after", action="store_true", help="only the cameras the --after commands print (no road picks)")
    ap.add_argument("--jar", help="this Country Roads jar instead of the build's (wg.py --jar)")
    ap.add_argument("--regions", help="a saved world: copy its overworld region/entities/poi .mca files over the scene (look at a real world's chunks)")
    ap.add_argument("--region-files", nargs="*", default=[], help="with --regions: only these files, e.g. r.-47.523.mca")
    a = ap.parse_args()
    x, z = a.at
    p = f"execute positioned {x} 80 {z} run countryroads"
    run = subprocess.run([sys.executable, str(ROOT / "dev/server/wg.py"), "--seed", a.seed, "--name", f"cap-{a.name}", "--port", str(a.port),
                          *(["--mods", *a.mods] if a.mods else []), *[f"--jvm={j}" for j in a.jvm], *(["--jar", a.jar] if a.jar else []),
                          f"{p} pregen {a.radius + 128}", *a.after, *([] if a.only_after else [f"{p} shots {a.radius}"])], capture_output=True, text=True)
    print(run.stdout)
    shots = [] if a.only else [l.split()[1:] for l in run.stdout.splitlines() if l.startswith("CR-SHOT ") and "none" not in l]
    shots += [s.split() for s in a.shot]
    if run.returncode or not shots:
        print("FAIL no shots")
        return 1
    world = ROOT / f"dev/server/.work/cap-{a.name}/world"
    scene = HERE / ".work" / a.name / "world"
    shutil.rmtree(scene.parent, ignore_errors=True)
    shutil.copytree(world, scene)
    if a.regions:
        src = Path(a.regions) / "dimensions/minecraft/overworld"
        dst = scene / "dimensions/minecraft/overworld"
        for sub in ("region", "entities", "poi"):
            for f in sorted((src / sub).glob("*.mca")):
                if a.region_files and f.name not in a.region_files:
                    continue
                (dst / sub).mkdir(parents=True, exist_ok=True)
                shutil.copy2(f, dst / sub / f.name)
    dp = scene / "datapacks/crcam"
    fn = dp / "data/crcam/function"
    fn.mkdir(parents=True)
    (dp / "pack.mcmeta").write_text('{"pack": {"description": "Country Roads camera (dev)", "min_format": [107, 1], "max_format": 121}}')
    tags = dp / "data/minecraft/tags/function"
    tags.mkdir(parents=True)
    (tags / "load.json").write_text('{"values": ["crcam:load"]}')
    (tags / "tick.json").write_text('{"values": ["crcam:tick"]}')
    (fn / "load.mcfunction").write_text(
        f"scoreboard objectives add crcam dummy\nscoreboard players set t crcam 0\ngamerule advance_time false\n"
        f"gamerule advance_weather false\ntime set {a.time}\nweather clear\n")
    lines = ["scoreboard players add t crcam 1", "gamemode spectator @a"]
    # segment 0 repeats the first shot while the client is still loading; its frame is dropped
    for i, s in enumerate([shots[0], *shots]):
        lo, hi = i * SEG, (i + 1) * SEG - 1
        if i == len(shots):
            hi = 1000000
        lines.append(f"execute if score t crcam matches {lo}..{hi} run tp @a {s[0]} {s[1]} {s[2]} {s[3]} {s[4]}")
    (fn / "tick.mcfunction").write_text("\n".join(lines) + "\n")
    out = HERE / "out" / a.name
    env = dict(os.environ, EVERY=str(SEG))
    cap = subprocess.run(["bash", str(WS / "tools/ClientCapture/run.sh"), "26.3", str(scene), str(out / "raw"), "40", str(len(shots) + 1)],
                         env=env, capture_output=True, text=True)
    frames = sorted((out / "raw").glob("*.png"))
    frames = frames[1:]
    if len(frames) < len(shots):
        print(cap.stdout[-2000:], cap.stderr[-2000:])
        print(f"FAIL got {len(frames)} frames for {len(shots)} shots")
        return 1
    for f, s in zip(frames, shots):
        shutil.copy(f, out / f"{s[5]}.png")
    named = [str(out / f"{s[5]}.png") for s in shots]
    subprocess.run(["wb", "img", "sheet", *named, "--out", str(out / "sheet.png")], capture_output=True)
    print("shots:", ", ".join(s[5] for s in shots), "->", out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
