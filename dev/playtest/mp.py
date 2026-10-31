#!/usr/bin/env python3
"""Run the "playtest" mp case of dev/modtest.json (no versions/ folder: jar from build/libs).  python3 dev/playtest/mp.py [--case x]"""
import sys
from pathlib import Path
T = Path("/home/emppu/Projects/Minecraft Datapacks/tools/ModTest")
sys.path.insert(0, str(T))
import mt  # noqa
ROOT = Path(__file__).resolve().parents[2]
def jar(self, mc, loader):
    ver = [l.split("=")[1].strip() for l in (ROOT / "gradle.properties").read_text().splitlines() if l.startswith("mod.version=")][0]
    return ROOT / "build/libs" / f"countryroads-{ver}+{mc}-fabric.jar"
mt.Mod.jar = jar
sys.argv = ["mt.py", str(ROOT), "mp", "--case", "playtest", "--no-build"] + sys.argv[1:]
mt.main()
