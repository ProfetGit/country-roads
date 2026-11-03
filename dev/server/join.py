#!/usr/bin/env python3
"""Join test: a dedicated server and a real client of one loader. The client joins, the server sends stake path notices
(`countryroads notice`), and the client logs each one it receives and the first HUD layer call after it
(-Dcountryroads.traceNotices=true). Checks the payload channel, registry sync and the HUD layer on that loader.

join.py --loader neoforge [--port 25651]     (Quilt runs the Fabric jar; takes a server and a client slot)
"""
import argparse
import re
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import wg  # noqa: E402
ms, slots = wg.ms, wg.slots
sys.path.insert(0, str(wg.WS / "tools/ModTest"))
import client  # noqa: E402

USER = "ModTester"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--loader", default="fabric", choices=ms.ALL_LOADERS)
    ap.add_argument("--port", type=int, default=25651)
    ap.add_argument("--seed", default="12345")
    a = ap.parse_args()
    work = HERE / ".work" / f"join-{a.loader}"
    shutil.rmtree(work, ignore_errors=True)
    (work / "server/mods").mkdir(parents=True)
    srv = work / "server"
    (srv / "eula.txt").write_text("eula=true\n")
    (srv / "server.properties").write_text(
        f"online-mode=false\nserver-ip=127.0.0.1\nserver-port={a.port}\nlevel-seed={a.seed}\nview-distance=4\n"
        "simulation-distance=4\nspawn-protection=0\ndifficulty=peaceful\nmax-tick-time=-1\npause-when-empty-seconds=-1\n"
        "enforce-secure-profile=false\nwhite-list=false\n")
    jar = wg.mod_jar(a.loader)
    fabric = a.loader in ("fabric", "quilt")
    for j in [jar, *([ms.fabric_api(wg.MC)] if fabric else [])]:
        shutil.copy(j, srv / "mods")
    log = srv / "server.log"
    res = {}
    with slots.slot("server", f"countryroads join {a.loader}"), open(log, "w") as out:
        proc = subprocess.Popen(ms.command(wg.MC, a.loader, srv), cwd=srv, stdin=subprocess.PIPE, stdout=out, stderr=subprocess.STDOUT, text=True)
        try:
            if not ms.wait_for(log, proc, r"Done \(\d", ms.BOOT_TIMEOUT):
                print("FAIL server did not start")
                return 1

            def run_client():
                with slots.slot("client", f"countryroads join {a.loader}"):
                    res.update(client.run(mc=wg.MC, loader=a.loader, out=work / "out", game=work / "game", mod_jars=[jar],
                                          fabric_api_jar=fabric, join=f"127.0.0.1:{a.port}", user=USER, timeout=240,
                                          props={"countryroads.traceNotices": "true"}))

            t = threading.Thread(target=run_client)
            t.start()
            joined = ms.wait_for(log, proc, rf"{USER} joined the game", 200)
            print("joined" if joined else "FAIL client did not join")
            if joined:
                time.sleep(8)
                for cmd in ["op " + USER, f"give {USER} countryroads:surveyors_stake 2", "countryroads notice 1 120 3 1 false", "countryroads notice 5 2 0 0 false"]:
                    proc.stdin.write(cmd + "\n")
                    proc.stdin.flush()
                    time.sleep(1)
                time.sleep(6)
            proc.stdin.write("stop\n")
            proc.stdin.flush()
            proc.wait(120)
            t.join(300)
        finally:
            if proc.poll() is None:
                proc.kill()
    stext = log.read_text(errors="replace")
    ctext = Path(res["log"]).read_text(errors="replace") if res.get("log") and Path(res["log"]).exists() else ""
    sent = re.findall(r"CR-NOTICE sent=(\d+) players=(\d+)", stext)
    recv = re.findall(r"CR-NOTICE-RECV code=(\d+) a=(\d+) thread=(\S+)", ctext)
    drawn = "CR-HUD-LAYER drawn" in ctext
    gave = re.search(r"Gave 2 \[Surveyor's Stake\]|Gave 2 \[", stext) is not None
    print(f"server: notices sent {sent}, stake given {gave}")
    print(f"client: received {recv}, hud layer called {drawn}, client log {res.get('log')}")
    for line in ctext.splitlines():
        if re.search(r"countryroads|Country Roads", line) and re.search(r"ERROR|Exception", line):
            print("CLIENT " + line)
    ok = joined and len(sent) == 2 and all(s == "1" for s, _ in sent) and len(recv) == 2 and drawn and all(th == "Render" or th.startswith("Render") for *_, th in recv)
    print(f"JOIN {a.loader} {'PASS' if ok else 'FAIL'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
