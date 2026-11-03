# Changelog

## 1.0.0-beta.2

- Now on NeoForge and Forge as well as Fabric and Quilt (Quilt uses the Fabric jar). Same roads on every loader: a world generated with the same seed gets the same roads, bridges, signposts and house paths.
- NeoForge and Forge need no extra library; Fabric and Quilt still need Fabric API.

## 1.0.0-beta.1

First public beta. Roads between villages generated with the world, over valleys, rivers, ravines and through rock, with bridges, tunnels, signposts, lamp posts, fences, roadside props, wheat fields and village streets. The Surveyor's Stake builds a free path from your door to the nearest road, or between two points. Worldgen settings and a Mod Menu screen, roads on maps, a speed bonus and no monsters on roads (all optional). Works with Terralith, Tectonic, Biomes O' Plenty, Geophilic and the Dungeons and Taverns, Towns and Towers and Repurposed Structures villages. Old chunks nobody has lived in get their roads when they load.

Known: villagers and traders do not use the roads yet.

## Development log

## 0.1.67 (unreleased)

- Two roads that run side by side to the same bridge now merge into one road instead of forking at the landing.
- The stake card moved to the right side of the screen and shows mouse and key icons instead of the words Right-click, Place and Break.

## 0.1.66 (unreleased)

- Sneak + right-click with the stake in hand now ends the path at the last stake (it only worked with an empty hand).
- The preview and the build card show the same block count (the path's length).
- The stake recipe unlocks when you pick up paper or sticks.
- The stake card is one small tooltip-style panel below the crosshair. The config screen is translatable.

## 0.1.65 (unreleased)

- The stake no longer writes to the chat or the action bar. A small card beside the crosshair says what the path is doing ("To the road · 177 blocks", "Ends at stake 2 · 144 blocks") and shows the one to three things you can do as key chips (Right-click Build, Sneak + Right-click End at last stake, Break Cancel). It shows while you hold a stake or look at one, and for a few seconds after anything changes. Failures give a short reason and one hint ("No way through", "Add stakes to steer round").
- The Surveyor's Stake tooltip lists the gestures.

## 0.1.64 (unreleased)

- Paths between two points: place the first stake, place a second one where the path should end, then sneak + right-click a stake. The path now runs from the first stake to the last one and joins no road. Sneak + right-click again to send it on to the nearest road as before.

## 0.1.62 (unreleased)

- Fixed bamboo floating in the air beside roads in bamboo jungles: the road's clear space took the lower blocks of a stalk and left the upper ones hanging. Bamboo, cactus and sugar cane now grow whole or not at all near a road.

## 0.1.61 (unreleased)

- Roads stay as fast to generate as before. The network is the same short-range one as in 0.1.54 (nearby villages joined as before), and only clusters of villages that would otherwise be cut off from each other get a long link (up to 2000 blocks). A long link is the one road whose plan a chunk waits for the longest, so these are kept to the few that are needed.
- Planning runs ahead of you: the roads of the ground around a player, and around where they are heading, are planned in the background, so exploring on foot, by boat or by elytra never waits for a road.
- Very long roads keep to a narrower corridor (no more than a 1200-block road gets), so a failed search costs less.

## 0.1.59 (unreleased)

- 75% fewer flowers and bushes beside roads (flower drifts, verge flowers and bushes, leaf bushes). Grass is unchanged.

## 0.1.58 (unreleased)

- Fixed grass blocks floating on top of bamboo (and cactus) beside roads: bamboo and cactus are not ground any more.

## 0.1.57 (unreleased)

- Fixed frozen bare grass where roadside props stood in snow, and trees being undone whole for a branch high over a bank.

## 0.1.56 (unreleased)

- When a road between two nearby villages cannot be built (water in the way), the villages are joined by another road if one is possible.

## 0.1.55 (unreleased)

- Roads up to 2000 blocks between far village groups; junction signposts no longer stand on the road they leave.

## 0.1.54 (unreleased)

- Settings file `config/countryroads.json` and a Mod Menu screen: walking and riding speed on roads, no monsters on roads, roads on maps, house paths, roads in old chunks, and for new worlds village streets, signposts and lamps, wayside camps, roadside finds and wheat fields (each world keeps the settings it was created with).

## 0.1.53 (unreleased)

- Roads reach villages with no streets (Towns and Towers inns and lighthouses) at a door.

## 0.1.51 (unreleased)

- Bridges: the deck stays at the water, never a viaduct from a hilltop; no bend in a bridge.

## 0.1.48 (unreleased)

- Works with Terralith, Tectonic, Biomes O' Plenty, Geophilic, Dungeons and Taverns, Towns and Towers and Repurposed Structures; modded village streets are left as they are and roads join them.

## 0.1.46 (unreleased)

- Roadside finds are rarer: a milestone every 250 blocks, lost carts, shrines and benches once in a few kilometres.

## 0.1.45 (unreleased)

- Wheat fields beside roads in plains: a big irregular field with water pockets and a scarecrow.

## 0.1.44 (unreleased)

- Roadside finds: cairn milestones (sometimes with a hidden stash), lost carts, wayside shrines, benches where the land drops away, hay carts near villages; wildflower verges by biome.

## 0.1.43 (unreleased)

- Roads leave a village over dry land, so a bridge no longer starts at the street.

## 0.1.40 (unreleased)

- Roads stay off mountain flanks and cut natural slopes instead of leaving a sheer wall.

## 0.1.37 (unreleased)

- Boardwalks over shallow water: a ragged ribbon of spruce slabs, flat trapdoors and campfire posts.

## 0.1.28 (unreleased)

- New road bridges: straight stone arch bridges and timber bridges, weathering and moss that grow from the water up.

## 0.1.27 (unreleased)

- On a road you walk 20% faster (Speed I) and mounts run 30% faster.

## 0.1.24 (unreleased)

- Village streets get the same look as the roads, and roads join them.

## 0.1.14 (unreleased)

- House paths use the medieval bridge for every crossing; the wood follows the biome.

## 0.1.0 (unreleased)

- First playable version: roads between villages generated with the world, signposts, lamp posts, wayside camps, fences and walls, no monsters on roads, roads on maps, roads added to chunks generated before the mod; the Surveyor's Stake builds a path from a house door to the nearest road.
