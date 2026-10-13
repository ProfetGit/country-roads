#!/bin/sh
# Road regression on seed 12345 (check + decor at -120,1916 r300, staketest A and E warm), then a second fresh run for the hash.
cd "$(dirname "$0")/../.."
P="execute positioned -120 70 1916 run countryroads"
python3 dev/server/wg.py --seed 12345 --name reg1 --port 25621 --xmx 6G --timeout 2400 -- "$P check 300" "$P decor 300" \
  "countryroads staketest -300 1960 warm" "countryroads staketest -400 2200 warm"
r1=$?
python3 dev/server/wg.py --seed 12345 --name reg2 --port 25621 --xmx 6G --timeout 2400 -- "$P check 300"
r2=$?
grep -h "CR-HASH" dev/server/.work/reg1/server.log dev/server/.work/reg2/server.log | sed 's/.*CR-HASH/CR-HASH/'
exit $((r1 + r2))
