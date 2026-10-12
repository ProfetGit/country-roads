# Country Roads — PRD (spec, not a tracker)

## Context
New mod: immersive roads between villages, generated with the world, plus a survival way to link a player's house to the network. Competitors (Countered's Settlement Roads 230K dl, RoadArchitect 93K dl) stop at 1.21.10, so 26.x is open. Must handle mountains, water, ravines, swamps and forests gracefully, and work with Tectonic, Terralith and Geophilic (all on 26.3).

## Decisions (user-approved 2026-10-12)
- Fabric 26.3 only first. Mod `mods/CountryRoads/`, id `countryroads`, package `io.github.profetgit.countryroads`. Server+client (`environment: *`), Fabric API hard dep, Mod Menu optional.
- Worldgen, chunk by chunk: plan from seed, place each piece as its chunk generates.
- Old worlds: build through barely-visited chunks; route around lived-in chunks (InhabitedTime ≥ ~1 h, plus ring buffer).
- Connects villages (#minecraft:village + bundled modded list + config) and player houses. Topology: MST + a few loops (greedy spanner). Land only, no ocean crossings.
- Terrain: passes/valleys first, hillside cuts with retaining walls, tunnels (supports, lanterns, arches). No switchbacks. Rivers bridged at the narrowest point; lakes skirted, causeway if the detour is huge. Ravines bridged by span (short: plank + rope/fence rails; long: stone arch/timber trestle). Swamps/mangroves: boardwalk on log posts. Forests: weave around big trees, remove small ones whole, occasional stump.
- Look: biome-matched worn blend (tags → `c:` tags → climate fallback), fuzzy edges, ruts. Width: main 3 (wobble), house path 2 → 1 at the door. Slopes smoothed with slabs + stairs, max grade. Roads join the nearest village street at its edge, fading into village path blocks.
- Decor: junction signposts (names + distance), lantern posts near villages and on bridges, bridge/tunnel details. Wayside stops reuse vanilla `abandoned_camp` structures, RARE (~1 per 1000–1500 road blocks, min spacing, skip near natural camps).
- House path: cheap Surveyor's Stake at the door → auto-route to nearest road → ghost preview + bill → confirm → materials from inventory/nearby chests (~1 per 2 blocks, planks for bridges) → rolls out door-to-road over ~3 s with dust/sound. Breaking the stake undoes (journal restore + refund).
- Perks: ~+10% speed for players/horses on roads; no hostile spawns on roads; traders prefer roads, villagers occasionally travel to neighbours (config, ship last); road colour on vanilla maps.

## Architecture (APIs verified in 26.3 source)
Packages under `io.github.profetgit.countryroads`:
- `net` — `VillageOracle` (predicts starts: `RandomSpreadStructurePlacement.getPotentialStructureChunk` + `isStructureChunk`, replay of `ChunkGenerator.createStructures` weighted pick + `findValidGenerationPoint`; street pieces from the stub give join points; old chunks read `structures.starts` NBT), `NetworkGraph` (relative-neighbourhood graph ≤ ~1200 blocks + greedy spanner t≈1.5), `NameGen`.
- `terrain` — `TerrainSampler` (memoised `ChunkGenerator.getBaseColumn`: generator-agnostic, gives ground/water/fluid), `BiomeSampler` (`BiomeSource.createCachingResolver`), `CostField` (slope, water, biome, lived-in chunks, predicted structures).
- `route` — `CoarseAStar` (16-block grid, ellipse bound, heading state, integer costs, deterministic tie-break), `RefineAStar` (4-block band), `Profiler` (grade clamp, segment kinds GROUND/CUT/FILL/TUNNEL/BRIDGE/CAUSEWAY/BOARDWALK), `Spline` (wobble, arc-length `s`).
- `plan` — `PlanStore` (SavedData index + compressed per-region NBT), `PlanService` (async prefetch, `await(chunk)` on worker threads only), `ChunkMask`.
- `paint` — `RoadPainter` (column-local rules, writes centre chunk only, surface Y always from the plan so borders match), `Palette`, `Steps`, `Structures`, `Decor`, `TreeClear` (retrofit).
- `gen` mixins — `ChunkStatusTasksMixin`: camp injection in `generateStructureStarts` (real structure starts, so `/locate` and terrain adaptation work); `await` at `generateFeatures` HEAD; paint at INVOKE `Blender.generateBorderTicks` (carvers, lakes, trees, structure pieces already present). `FeaturePlacerMixin` cancels trees/huge mushrooms/blobs/lakes whose origin is in the corridor. `WorldGenRegionMixin` blocks neighbour writes into a painted corridor. Road mask persisted as a Fabric chunk attachment.
- `retro` — `ChunkScan` (`chunkScanner().scanChunk` for InhabitedTime/Status without loading), `RetrofitQueue` (CHUNK_LOAD + END_LEVEL_TICK, ~2 ms budget, re-checks inhabited time).
- `path` — `SurveyorStakeBlock`, `PathRouter` (time-sliced fine A* on real blocks, radius 192, natural-block allowlist), `PathJob` (journal + wave rollout, pattern from `mods/BuildBuddy/.../paste/PasteJob.java`), `Bill`, payloads (`PathPreview`, `PathConfirm`, `PathCancel`, `PathResult`).
- `perk` — speed via transient `MOVEMENT_SPEED` modifier; spawn guard in `NaturalSpawner.isValidSpawnPostitionForType`; map colour wrap in `MapItem.update`; `TraderRoads` (`WanderingTraderSpawner` + `setWanderTarget` waypoints); `RoadTripBehavior` (rare, restricted to unemployed/nitwit).
- `client` — `GhostPreview` (always-on-top, Iris-safe like BuildBuddy), `BillHud`. `config` — JSON + Mod Menu screen (pattern: `mods/HavingABlast/.../ModMenuEntry.java`). `dev` — `/countryroads villages|plan|pregen|stats|check`, `ModTestHook`.

Build files copied from `mods/BuildBuddy/build.gradle.kts` + `gradle.properties` + `gradle/gradle-daemon-jvm.properties`. Server tests follow `mods/HavingABlast/dev/server/repairtest.py`.

## Milestones (commit + STATUS.md each; done only when its check passes)
- **M0 Scaffold** — build, fabric.mod.json, mixins, CLAUDE.md/PRD.md/STATUS.md (excluded), git, `dev/modtest.json`. Check: `./gradlew build`, `mt.py CountryRoads quick` (boot + mixin audit).
- **M1 Oracle + sampler** — Check: headless pregen of 2k radius on 3 seeds, predicted villages == real starts (100%); `getBaseColumn` µs logged.
- **M2 One road, two villages, plains seed** — Check: zero border breaks, surface == planned Y, same-seed hash equal across two servers and after mid-pregen restart, MSPT p99 < 50.
- **M3 Network** — Check: 4k×4k pregen, components == land components, no ocean edges, plan CPU per region within budget.
- **M4 Terrain** — cuts/walls, tunnels, bridges (planned + ravine), causeways, boardwalks, guards. Check: automated scan for unsmoothed steps, floating blocks/leaves, clearance, water on road, on seeds with rivers/ravines/mountains/swamps.
- **M5 Look** — palettes, edges, ruts, village join. Check: classifier unit tests + per-biome client stills (eye review).
- **M6 Decor + camps** — Check: every 3+ junction has a correct sign; camp density/spacing in range; `/locate` finds injected camps.
- **M7 Terrain-mod compat** — Tectonic, Terralith, Geophilic. Ask before downloading the jars. Check: M3–M5 scans pass, no crash at height limits.
- **M8 Retrofit** — Check: vanilla world → mark chunks lived-in → reopen with mod: lived-in chunk hashes unchanged, roads elsewhere, MSPT p99 < 50.
- **M9 House paths** — Check: server scenario stake → preview → confirm → rollout → break stake restores identical hash + correct refund; client still of ghost + bill.
- **M10 Perks** — Check: 0 monster spawns on road columns vs control under `/tick sprint`; map pixels coloured; speed modifier on/off; trader follows waypoints.
- **M11 Config, Mod Menu, perf pass, release jar**, then art/showcase via the usual skills.

## Risks
1. Planning cost slows chunk loading → hierarchical grids, lazy memoised samples, prefetch ahead of players; benchmark in M1.
2. Determinism → integer costs, total-order tie-breaks, persisted plans, hash tests.
3. Oracle mismatch with modded placements → `isStructureChunk` scan fallback, read real starts for existing chunks, dev compare command per modpack.
4. Tectonic/Terralith heights → all limits from the level, cap road Y, heavy cost at extremes.
5. Border artifacts → column-local rules, plan-fixed Y, feature + region guards, ravine span rule ≤ 16.
6. Villager trips breaking job/home memory → restrict, config, ship last.
7. A base in a low-inhabited chunk → ring buffer + "non-natural block" heuristic.

## Verification
Per milestone as above: `./gradlew build`, `python3 tools/ModTest/mt.py CountryRoads quick|full`, headless server scripts in `mods/CountryRoads/dev/server/` (pregen, scans, hashes, MSPT), all heavy runs through `wb job run --slot server|client`. Final: install the jar in the Fabric 26.3 profile for the user's hands-on test.
