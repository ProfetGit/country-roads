#!/bin/sh
# Stills of the dry-country road palettes (0 = 0.1.4, a/b/c = options) on the user's seed, badlands + desert roads.
set -e
cd "$(dirname "$0")/../.."
for v in 0 a b c; do
  python3 dev/capture/shoot.py --seed 2475282789905510192 --at -390 -490 --radius 100 --name dry-$v --port 25611 --only \
    --jvm=-Dcountryroads.dry=$v \
    --shot "-445 74 -448 -152 14 desert" --shot "-428 96 -468 -150 62 desert_top" \
    --shot "-352 86 -486 -117 16 badlands" --shot "-330 104 -500 -95 64 badlands_top"
done
