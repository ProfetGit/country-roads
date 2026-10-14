#!/bin/sh
# Cut/face measurement (countryroads cuts) against the same world generated without painting (the true natural land):
# the user's world spots, the reported mountain spot and seed 12345, plus check/docks/decor/roads on the painted run.
# Usage: [JAR=x.jar] dev/server/cutbench.sh TAG [port]  ->  dev/server/.work/cut-TAG-*/server.log, summary on stdout.
cd "$(dirname "$0")/../.."
T=${1:-run}; PORT=${2:-25711}
U=2475282789905510192
REF=$(pwd)/dev/server/.work/cutref-$T
mkdir -p "$REF"

# area NAME SEED X Z R
area() {
  name=$1; seed=$2; x=$3; z=$4; r=$5
  P="execute positioned $x 80 $z run countryroads"
  python3 dev/server/wg.py ${JAR:+--jar $JAR} ${JVMX:+--jvm=$JVMX} --seed $seed --name cut-$T-$name-ref --port $PORT --xmx 8G --timeout 3000 \
    --jvm=-Dcountryroads.noPaint=true --jvm=-Dcountryroads.cutRef=$REF/$name.bin -- "$P pregen $r" "$P cuts $r" > /dev/null
  python3 dev/server/wg.py ${JAR:+--jar $JAR} ${JVMX:+--jvm=$JVMX} --seed $seed --name cut-$T-$name --port $PORT --xmx 8G --timeout 3000 \
    --jvm=-Dcountryroads.cutRef=$REF/$name.bin -- "$P pregen $r" "$P check $r" "$P cuts $r" "$P docks $r" "$P decor $r" "$P roads $r" > /dev/null
  echo "== $name"
  grep -h 'CR-CUT \|CR-CHECK\|CR-DOCKS\|CR-DECOR' dev/server/.work/cut-$T-$name/server.log | grep -v 'System chat' | sed 's/.*\(CR-\)/\1/' | cut -c1-230
  grep -h 'CR-ROAD ' dev/server/.work/cut-$T-$name/server.log | grep -v 'System chat' | sed -n 's/.*ms=\([0-9]*\).*/\1/p' | awk '{s+=$1; if ($1>m) m=$1; n++} END {print "routeMs sum=" s " max=" m " n=" n}'
}

area spot $U -23971 268226 300
area u1 $U -350 -490 200
area u2 $U 980 -60 200
area u3 $U -1340 -205 200
area u4 $U -143 -271 250
area u5 $U 72 -7 250
area u6 $U -1900 -200 250
area reg 12345 -120 1916 300
