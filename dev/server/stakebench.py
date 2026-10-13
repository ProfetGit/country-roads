#!/usr/bin/env python3
"""House-path survey benchmark on fixed spots of seed 12345 (see the mod CLAUDE.md).

stakebench.py --tag base [--modes warm cold] [--spots A B ...]

warm: one world, every spot surveyed with its whole 330-block window force-loaded first.
cold: a fresh world per spot, nothing loaded around the stake (chunks generate on demand).
Prints one row per spot and mode and saves dev/server/bench/<tag>.json; --compare <tag> prints the old rows beside.
"""
import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPOTS = {
    "A": (-300, 1960),   # temperate terraced hill, ~96-block route
    "B": (-120, 1750),   # ~186-block route
    "C": (-500, 1700),   # hill, default bore cost tunnels
    "D": (-300, 2090),   # stony mountain
    "E": (-400, 2200),   # forest, road ~260 away
    "F": (-800, 1700),   # forest, road ~280 away
    "G": (-900, 1300),   # birch forest, no road within the window: must fail fast
}
KEYS = ("waits", "waitMs", "wallMs", "mainMsSum", "mainMsMax", "tickP99", "allocMB", "chunksReq", "chunksSync", "expansions", "candidates")


JAR: list[str] = []


def run(name: str, cmds: list[str], timeout: int) -> str:
    p = subprocess.run([sys.executable, str(HERE / "wg.py"), "--seed", "12345", "--name", name, "--timeout", str(timeout), *JAR, "--", *cmds],
                       capture_output=True, text=True)
    return p.stdout + p.stderr


def parse(out: str) -> dict:
    rows, cur = {}, None
    for line in out.splitlines():
        m = re.search(r"CR-STAKE-PERF (\S+) (.*) at=(-?\d+),(-?\d+)", line)
        if m:
            cur = f"{m[3]},{m[4]}"
            r = rows.setdefault(cur, {})
            r["outcome"] = m[1]
            for k, v in re.findall(r"(\w+)=([-\d.]+)", m[2]):
                r[k] = float(v)
            continue
        if cur is None:
            continue
        r = rows[cur]
        if m := re.search(r"CR-STAKE-COST direct=(-?\d+) final=(-?\d+) length=(-?\d+) tunnelCells=(-?\d+)", line):
            r["costDirect"], r["costFinal"], r["length"], r["tunnelCells"] = int(m[1]), int(m[2]), int(m[3]), int(m[4])
        elif m := re.search(r"CR-STAKE-WALK (\w+) cells=(\d+)", line):
            r["walk"], r["cells"] = m[1], int(m[2])
        elif m := re.search(r"CR-STAKE-SIM (\w+)", line):
            r["sim"] = m[1]
        elif m := re.search(r"CR-STAKE-TREES logs removed (\d+)", line):
            r["logs"] = int(m[1])
        elif m := re.search(r"CR-STAKE (PASS|FAIL) cells=(\d+).*notRestored=(\d+)", line):
            r["restore"], r["notRestored"] = m[1], int(m[3])
        elif m := re.search(r"CR-STAKE-TUNNEL (\w+) cells=(\d+)", line):
            r["tunnel"] = int(m[2])
    return rows


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", required=True)
    ap.add_argument("--modes", nargs="*", default=["warm", "cold"])
    ap.add_argument("--spots", nargs="*", default=list(SPOTS))
    ap.add_argument("--compare")
    ap.add_argument("--timeout", type=int, default=1800)
    ap.add_argument("--jar", help="bench this jar (copied first, so rebuilding mid-run cannot change it)")
    ap.add_argument("--pre", nargs="*", default=[], help="commands before the stake tests (e.g. 'countryroads surveyweight 0')")
    a = ap.parse_args()
    if a.jar:
        import shutil
        frozen = HERE / "bench" / f"{a.tag}.jar"
        (HERE / "bench").mkdir(exist_ok=True)
        if Path(a.jar).resolve() != frozen.resolve():
            shutil.copy(a.jar, frozen)
        JAR.extend(["--jar", str(frozen)])
    res = {}
    for mode in a.modes:
        if mode == "warm":
            cmds = a.pre + [f"countryroads staketest {SPOTS[s][0]} {SPOTS[s][1]} warm" for s in a.spots]
            out = run(f"bench-{a.tag}-warm", cmds, a.timeout)
            rows = parse(out)
            for s in a.spots:
                res[f"{s}/warm"] = rows.get(f"{SPOTS[s][0]},{SPOTS[s][1]}", {"outcome": "MISSING"})
        else:
            for s in a.spots:
                out = run(f"bench-{a.tag}-cold-{s}", a.pre + [f"countryroads staketest {SPOTS[s][0]} {SPOTS[s][1]} cold"], a.timeout)
                res[f"{s}/cold"] = parse(out).get(f"{SPOTS[s][0]},{SPOTS[s][1]}", {"outcome": "MISSING", "tail": out[-600:]})
    (HERE / "bench").mkdir(exist_ok=True)
    path = HERE / "bench" / f"{a.tag}.json"
    merged = json.loads(path.read_text()) if path.is_file() else {}
    merged.update(res)
    path.write_text(json.dumps(merged, indent=1))
    old = json.loads((HERE / "bench" / f"{a.compare}.json").read_text()) if a.compare else {}
    cols = ("outcome", "wallMs", "waits", "waitMs", "mainMsSum", "mainMsMax", "chunksReq", "chunksSync", "expansions", "allocMB", "costDirect", "length", "walk", "sim", "restore", "logs", "tunnelCells", "candidates")
    print("spot     " + " ".join(f"{c:>10.10}" for c in cols))
    for k, r in res.items():
        print(f"{k:8} " + " ".join(f"{str(r.get(c, '')):>10.10}" for c in cols))
        if k in old:
            o = old[k]
            print(f"{'  ' + a.compare:8.8} " + " ".join(f"{str(o.get(c, '')):>10.10}" for c in cols))
    return 0


if __name__ == "__main__":
    sys.exit(main())
