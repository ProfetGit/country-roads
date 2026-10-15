#!/usr/bin/env python3
"""Headless worldgen harness: boot a real 26.3 Fabric dedicated server with Country Roads + Fabric API on a seed,
run commands, print the CR-* result lines.

wg.py --seed 123 [--keep] [--mods extra.jar ...] [--name run] -- "countryroads oraclecheck 1024" "countryroads sample 2000"

Work dir: dev/server/.work/<name>/ (world kept with --keep so a second run reuses it). Exit 1 when the server fails,
a command times out, or a line contains FAIL. Takes a machine-wide server slot (tools/ModTest/slots.py).
"""
import argparse
import importlib.util
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
WS = ROOT.parents[1]
sys.path.insert(0, str(WS / "tools/ModJar"))
_spec = importlib.util.spec_from_file_location("modjar_smoke", WS / "tools/ModJar/smoke.py")
ms = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ms)
sys.path.insert(0, str(WS / "tools/ModTest"))
import slots  # noqa: E402

MC = "26.3"


def mod_jar() -> Path:
    version = re.search(r"^mod\.version=(.+)$", (ROOT / "gradle.properties").read_text(), re.M).group(1).strip()
    jar = ROOT / "build/libs" / f"countryroads-{version}+{MC}-fabric.jar"
    if not jar.is_file():
        sys.exit(f"no {jar.name}: run ./gradlew build first")
    return jar


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", default="0")
    ap.add_argument("--name", default="wg")
    ap.add_argument("--keep", action="store_true", help="keep the world from the last run of this name")
    ap.add_argument("--mods", nargs="*", default=[])
    ap.add_argument("--jar", help="this Country Roads jar instead of the build's")
    ap.add_argument("--port", type=int, default=25591)
    ap.add_argument("--timeout", type=float, default=900)
    ap.add_argument("--xmx", default="4G")
    ap.add_argument("--no-mod", action="store_true", help="vanilla + Fabric API only (old-world tests); waits --wait s after commands")
    ap.add_argument("--wait", type=float, default=60)
    ap.add_argument("--jvm", action="append", default=[], help="extra JVM arg, repeatable: --jvm=-Dcountryroads.retrofit.paused=true")
    ap.add_argument("--config", help="copy this file to config/countryroads.json before boot")
    ap.add_argument("cmds", nargs="*")
    a = ap.parse_args()

    work = HERE / ".work" / a.name
    if not a.keep:
        shutil.rmtree(work, ignore_errors=True)
    shutil.rmtree(work / "mods", ignore_errors=True)
    (work / "mods").mkdir(parents=True)
    (work / "tmp").mkdir(exist_ok=True)
    (work / "eula.txt").write_text("eula=true\n")
    if a.config:
        (work / "config").mkdir(exist_ok=True)
        shutil.copy(a.config, work / "config" / "countryroads.json")
    (work / "server.properties").write_text(
        f"online-mode=false\nserver-ip=127.0.0.1\nserver-port={a.port}\nlevel-seed={a.seed}\n"
        "generate-structures=true\nview-distance=4\nsimulation-distance=4\nspawn-protection=0\ndifficulty=peaceful\n"
        "max-tick-time=-1\npause-when-empty-seconds=-1\nsync-chunk-writes=false\nenable-query=false\nenable-rcon=false\n")
    for j in [*([] if a.no_mod else [Path(a.jar) if a.jar else mod_jar()]), ms.fabric_api(MC), *map(Path, a.mods)]:
        shutil.copy(j, work / "mods")
    cmd = ms.command(MC, "fabric", work)
    cmd = [c.replace("-Xmx2G", f"-Xmx{a.xmx}") for c in cmd]
    cmd = cmd[:1] + a.jvm + cmd[1:]
    log = work / "server.log"
    ok = True
    with slots.slot("server", f"countryroads {a.name}"), open(log, "w") as out:
        t0 = time.time()
        proc = subprocess.Popen(cmd, cwd=work, stdin=subprocess.PIPE, stdout=out, stderr=subprocess.STDOUT, text=True)
        try:
            if not ms.wait_for(log, proc, r"Done \(\d", ms.BOOT_TIMEOUT):
                print("FAIL server did not start")
                print("\n".join(ms.tail(log, 30)))
                return 1
            print(f"boot {time.time() - t0:.0f} s")
            for c in a.cmds:
                proc.stdin.write(c + "\n")
            proc.stdin.flush()
            end = time.time() + a.timeout
            if a.no_mod:
                time.sleep(a.wait)
            while not a.no_mod:
                proc.stdin.write("countryroads idle\n")
                proc.stdin.flush()
                if ms.wait_for(log, proc, r"CR-IDLE", 3):
                    break
                if time.time() > end or proc.poll() is not None:
                    print(f"FAIL commands did not finish in {a.timeout:.0f} s")
                    ok = False
                    break
            proc.stdin.write("stop\n")
            proc.stdin.flush()
            try:
                proc.wait(120)
            except subprocess.TimeoutExpired:
                print("FAIL server did not stop")
                ok = False
        finally:
            if proc.poll() is None:
                proc.kill()
                proc.wait()
    text = log.read_text(errors="replace")
    seen = set()
    for line in text.splitlines():
        if "CR-" in line:
            res = line[line.index("CR-"):]
            if res in seen or res == "CR-IDLE":
                continue
            seen.add(res)
            print(res)
            if "FAIL" in line:
                ok = False
        elif re.search(r"\b(ERROR|Exception)\b", line) and "countryroads" in text:
            if "Exception" in line or "/ERROR]" in line:
                print("LOG " + line)
    print(f"total {time.time() - t0:.0f} s, log {log}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
