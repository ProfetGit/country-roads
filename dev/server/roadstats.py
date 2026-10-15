#!/usr/bin/env python3
"""Summarise CR-ROADSTAT/CR-BRIDGE lines of a wg.py run: longest bridge, sharpest deck bend, longest/tallest fill.
roadstats.py <server.log> [...]"""
import re
import sys

for f in sys.argv[1:]:
    st, br = {}, {}
    for l in open(f, errors="replace"):
        m = re.search(r"CR-ROADSTAT (.*?) bridgeMax=(\d+)\S* deckBend=(\d+)(\S*) fillRun=(\d+)(\S*) fillMax=(\d+)(\S*)", l)
        if m:
            st[m.group(1)] = (int(m.group(2)), int(m.group(3)), m.group(4), int(m.group(5)), m.group(6), int(m.group(7)), m.group(8))
        m = re.search(r"CR-BRIDGE at (\S+ \S+ \S+) len=(\d+) style=(\w+)", l)
        if m:
            br[m.group(1)] = (int(m.group(2)), m.group(3))
    if not st:
        print(f, "no CR-ROADSTAT")
        continue
    lens = sorted((v[0] for v in br.values()), reverse=True)
    bend = max(st.items(), key=lambda kv: kv[1][1])
    run = max(st.items(), key=lambda kv: kv[1][3])
    tall = max(st.items(), key=lambda kv: kv[1][5])
    print(f"{f}\n  roads={len(st)} bridges={len(br)} longest={lens[:5]} total={sum(lens)} >40:{sum(1 for x in lens if x > 40)}"
          f"\n  maxDeckBend={bend[1][1]}{bend[1][2]}  longestFill(>=4)={run[1][3]}{run[1][4]}  tallestFill={tall[1][5]}{tall[1][6]} ({tall[0]})"
          f"\n  fillRuns>=20: {sum(1 for v in st.values() if v[3] >= 20)}  fillMax>=8: {sum(1 for v in st.values() if v[5] >= 8)}  bends>15: {sum(1 for v in st.values() if v[1] > 15)}")
