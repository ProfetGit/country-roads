#!/bin/bash
# Client stills for compat configs: shoot.py with the config's jars on server and client.
cd "$(dirname "$0")/../.."
FAPI=$(ls ~/.local/share/ModrinthApp/profiles/"Fabric 26.3"/mods/fabric-api-*.jar | head -1)
CR=$(ls -t versions/26.3-fabric/build/libs/countryroads-*+26.3-fabric.jar | head -1)
J=dev/compat/jars
shoot() { # name seed x z jars...
  local name=$1 seed=$2 x=$3 z=$4; shift 4
  local extra="$FAPI:$CR"; for j in "$@"; do extra="$extra:$j"; done
  EXTRA_MODS="$extra" python3 dev/capture/shoot.py --seed "$seed" --at "$x" "$z" --radius 200 --name "$name" --port 25661 --mods "$@"
}
TNT=($J/t_and_t-*.jar $J/cristellib-*.jar $J/cloth-config-*.jar)
BOP=($J/BiomesOPlenty-*.jar $J/TerraBlender-*.jar $J/GlitchCore-*.jar)
TER=($J/Terralith_*.jar $J/lithostitched-*.jar)
ALL=($(ls $J/*.jar))
shoot compat-tnt 12345 -2214 -1191 "${TNT[@]}"
shoot compat-bop 2475282789905510192 -2532 -388 "${BOP[@]}"
shoot compat-terralith 2475282789905510192 -2539 -2132 "${TER[@]}"
shoot compat-all 2475282789905510192 -804 -996 "${ALL[@]}"
