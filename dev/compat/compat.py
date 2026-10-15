#!/usr/bin/env python3
"""Terrain/structure mod compat matrix: each mod alone and all together, on two seeds.

compat.py [--configs a b ...] [--seeds s ...] [--jar J] [--tag T]
Phase 1 (fresh world): oraclecheck 2048, roads 2500 around 0,0.
Phase 2 (same world, --keep): check/decor/props/fields/docks/streets around the village with the most roads.
Writes dev/compat/out/<tag>/<config>-<seed>.txt (CR lines) and summary.tsv.
"""
import argparse, re, subprocess, sys
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
J = HERE / "jars"


def jars(*pats):
    out = []
    for p in pats:
        m = sorted(J.glob(p))
        if not m:
            sys.exit(f"missing jar {p}")
        out.append(str(m[0]))
    return out


LITHO = "lithostitched-*.jar"
CONFIGS = {
    "vanilla": [],
    "terralith": jars("Terralith_*.jar", LITHO),
    "tectonic": jars("tectonic-*.jar", LITHO),
    "bop": jars("BiomesOPlenty-*.jar", "TerraBlender-*.jar", "GlitchCore-*.jar"),
    "geophilic": jars("Geophilic_*.jar"),
    "dnt": jars("dungeons-and-taverns-*.jar"),
    "tnt": jars("t_and_t-*.jar", "cristellib-*.jar", "cloth-config-*.jar"),
    "rs": jars("repurposed_structures-*.jar", "midnightlib-*.jar"),
}
CONFIGS["all"] = sorted({j for v in CONFIGS.values() for j in v})

SEEDS = ["12345", "2475282789905510192"]
VERDICT = re.compile(r"(CR-[A-Z-]+) (PASS|FAIL)")


def run(name, seed, mods, cmds, jar, keep, port):
    args = [sys.executable, str(ROOT / "dev/server/wg.py"), "--seed", seed, "--name", name, "--port", str(port),
            "--xmx", "6G", "--timeout", "3600"]
    if keep:
        args.append("--keep")
    if jar:
        args += ["--jar", jar]
    if mods:
        args += ["--mods", *mods]
    args += ["--", *cmds]
    p = subprocess.run(args, cwd=ROOT, capture_output=True, text=True)
    log = ROOT / "dev/server/.work" / name / "server.log"
    text = log.read_text(errors="replace") if log.exists() else ""
    return p.returncode, p.stdout + p.stderr, text


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--configs", nargs="*", default=list(CONFIGS))
    ap.add_argument("--seeds", nargs="*", default=SEEDS)
    ap.add_argument("--jar")
    ap.add_argument("--tag", default="run")
    ap.add_argument("--port", type=int, default=25641)
    ap.add_argument("--phase1-only", action="store_true")
    a = ap.parse_args()
    out = HERE / "out" / a.tag
    out.mkdir(parents=True, exist_ok=True)
    summary = []
    for cfg in a.configs:
        for seed in a.seeds:
            name = f"compat-{cfg}-{seed[:6]}"
            rc, so, log = run(name, seed, CONFIGS[cfg], ["countryroads oraclecheck 2048", "countryroads roads 2500"], a.jar, False, a.port)
            lines = [l for l in log.splitlines() if "CR-" in l or "Exception" in l or "ERROR" in l]
            ends = Counter()
            for m in re.finditer(r"CR-ROAD (-?\d+), (-?\d+), (-?\d+) -> (-?\d+), (-?\d+), (-?\d+)", log):
                ends[(int(m[1]), int(m[3]))] += 1
                ends[(int(m[4]), int(m[6]))] += 1
            nroads = len(re.findall(r"CR-ROAD ", log))
            row = {"cfg": cfg, "seed": seed, "boot": "OK" if "Done (" in log else "FAIL", "roads": nroads}
            m = re.search(r"CR-ORACLE (\w+) .*?predicted=(\d+) actual=(\d+)", log)
            row["oracle"] = f"{m[1]} {m[2]}/{m[3]}" if m else "none"
            if ends and not a.phase1_only:
                (x, z), _ = ends.most_common(1)[0]
                P = f"execute positioned {x} 70 {z} run countryroads"
                rc2, so2, log2 = run(name, seed, CONFIGS[cfg], [f"{P} check 300", f"{P} decor 300", f"{P} props 300",
                                                               f"{P} fields 300", f"{P} docks 600", f"{P} streets 300"],
                                     a.jar, True, a.port)
                lines += [f"--- phase2 at {x},{z}"] + [l for l in log2.splitlines() if "CR-" in l or "Exception" in l or "ERROR" in l]
                row["spot"] = f"{x},{z}"
                for k, v in VERDICT.findall(log2):
                    row[k] = v
                dm = re.search(r"CR-DOCKS.*", log2)
                if dm:
                    row["docks"] = dm[0][:80]
                so += so2
            (out / f"{cfg}-{seed[:6]}.txt").write_text("\n".join(lines) + "\n\n" + so)
            summary.append(row)
            print(row, flush=True)
    keys = sorted({k for r in summary for k in r}, key=lambda k: (k not in ("cfg", "seed", "boot", "oracle", "roads", "spot"), k))
    with open(out / "summary.tsv", "w") as f:
        f.write("\t".join(keys) + "\n")
        for r in summary:
            f.write("\t".join(str(r.get(k, "")) for k in keys) + "\n")


if __name__ == "__main__":
    main()
