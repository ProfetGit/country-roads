#!/bin/sh
# Loader parity on seed 12345 at -120,1916: fresh check/decor/staketest A, then the same world restarted (saved road
# columns read back from disk: check, then perks on a second restart; perks' off-road spawn sample read 0/400 on the
# first restart after generation on every loader, 89-114/400 after). Usage: dev/server/loaders.sh fabric|quilt|neoforge|forge [port]
cd "$(dirname "$0")/../.."
L=${1:-fabric}; PORT=${2:-25631}
P="execute positioned -120 70 1916 run countryroads"
python3 dev/server/wg.py --loader "$L" --seed 12345 --name "ld-$L" --port "$PORT" --xmx 6G --timeout 2400 -- "$P check 300" "$P decor 300" \
  "countryroads staketest -300 1960 warm"
r1=$?
python3 dev/server/wg.py --loader "$L" --seed 12345 --name "ld-$L" --keep --port "$PORT" --xmx 6G --timeout 2400 -- "$P check 300"
r2=$?
python3 dev/server/wg.py --loader "$L" --seed 12345 --name "ld-$L" --keep --port "$PORT" --xmx 6G --timeout 2400 -- "difficulty hard" "time set midnight" "$P perks 300"
r3=$?
echo "LOADER $L run1=$r1 run2=$r2 run3=$r3"
grep -h "CR-HASH" "dev/server/.work/ld-$L/server.log" | sed 's/.*CR-HASH/CR-HASH/'
exit $((r1 + r2 + r3))
