#!/usr/bin/env python3
"""Playtest runner for Country Roads (mt.Mod.jar is pointed at versions/<mc>-<loader>/build/libs).
  python3 dev/playtest/run.py dev/playtest/x.txt [--addon ..]     (always --no-build)
A script's first lines may carry  `# config: {json}` (written to config/countryroads.json of its game dir)
and `# options: {json}` (client options, e.g. {"gui_scale": 3})."""
import json, sys
from pathlib import Path
T = Path("/home/emppu/Projects/Minecraft Datapacks/tools/ModTest")
sys.path.insert(0, str(T))
import mt, playtest  # noqa
ROOT = Path(__file__).resolve().parents[2]
def jar(self, mc, loader):
    ver = [l.split("=")[1].strip() for l in (ROOT / "gradle.properties").read_text().splitlines() if l.startswith("mod.version=")][0]
    p = ROOT / f"versions/{mc}-{'fabric' if loader == 'quilt' else loader}/build/libs" / f"countryroads-{ver}+{mc}-{'fabric' if loader == 'quilt' else loader}.jar"
    if not p.is_file():
        raise SystemExit(f"missing {p}")
    return p
mt.Mod.jar = jar
scripts = [a for a in sys.argv[1:] if a.endswith(".txt")]
hdr = {}
for s in scripts:
    p = Path(s).resolve() if Path(s).exists() else ROOT / s
    for line in p.read_text().splitlines()[:6]:
        for k in ("config", "options"):
            if line.startswith(f"# {k}:"):
                hdr[(p.stem, k)] = json.loads(line.split(":", 1)[1])
    cfg = hdr.get((p.stem, "config"))
    g = T / ".work/CountryRoads/playtest" / p.stem / "game/config"
    g.mkdir(parents=True, exist_ok=True)
    f = g / "countryroads.json"
    f.unlink(missing_ok=True)
    if cfg is not None:
        f.write_text(json.dumps(cfg))
opts = {}
for (stem, k), v in hdr.items():
    if k == "options":
        opts.update(v)
orig = mt.Mod.__init__
def init(self, name):
    orig(self, name)
    o = dict(opts)
    for k in ("width", "height"):
        if k in o: self.m[k] = o.pop(k)
    self.m["options"] = {**self.m.get("options", {}), **o}
mt.Mod.__init__ = init
sys.exit(playtest.main([str(ROOT)] + sys.argv[1:] + ["--no-build"]))
