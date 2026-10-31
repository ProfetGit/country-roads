#!/bin/sh
# FX sprites are our own (Veinminer / Traveler's Lantern); copied so this icon stays self-contained
D="$(dirname "$0")/sprites"
V=../../../../packs/Veinminer/dev/icon/sprites
L=../../../BeltLantern/dev/icon/sprites
cd "$(dirname "$0")"
for n in fx_star fx_ring fx_spark; do cp $V/$n.png $V/$n.aseprite sprites/; done
cp $L/fx_dust.png $L/fx_dust.aseprite sprites/
