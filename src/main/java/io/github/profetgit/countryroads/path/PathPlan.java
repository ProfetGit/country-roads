package io.github.profetgit.countryroads.path;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.paint.Palette;
import net.minecraft.world.level.ChunkPos;

/**
 * From the walked line to the cells to build: a graded height profile, a bed three blocks wide at the path's height
 * (cut into the slope or filled up to it), soft banks beside the bed, tunnels, and the bill.
 */
final class PathPlan {
    private PathPlan() {}

    static final int[] DX = HousePaths.DX, DZ = HousePaths.DZ;
    /** How far the walked line may sit above or below its ground. */
    static final int PROFILE_RANGE = 2;
    /** Most a bed column or a bank column is cut or filled; banks reach this many columns beyond the bed. */
    static final int BED_MAX = 3, BANK_MAX = 4, BANK_REACH = 3;
    /** Bridge rail posts every this many cells (and at both ends). */
    static final int BRIDGE_POST = 4, BRIDGE_GAP = 3;
    /** Crossings at least this long get the kit bridge (BridgeKit); shorter ones keep the plank deck. */
    static final int KIT_MIN = 3;

    record BlockPosLike(int x, int z) {}

    /** The column of a kit bridge's local (x across, z along). */
    static BlockPosLike kitColumn(BridgeKit.Bridge br, int x, int z) {
        var p = BridgeKit.world(br, x, 0, z);
        return new BlockPosLike(p.getX(), p.getZ());
    }
    /** Crossings at least ARCH_MIN cells long arch by one block, from ARCH_HIGH by ARCH_MAX half blocks. */
    static final int ARCH_MIN = 6, ARCH_HIGH = 16, ARCH_MAX = 4;
    /**
     * Profile costs: per block cut, per block filled, a step, a step right after a step (a flight: stairs instead of a
     * slab), a step beside a corner.
     */
    static final int W_CUT = 7, W_FILL = 9, W_STEP = 12, W_FLIGHT = 40, W_CORNER = 400;

    /** The planned surface, stair facing (-1 none) and half step (bottom slab) of every centre cell. */
    record Plan(int[] y, int[] stair, boolean[] slab) {}

    static int dirOf(int dx, int dz) {
        for (int d = 0; d < 4; d++) if (DX[d] == dx && DZ[d] == dz) return d;
        return 0;
    }

    /**
     * Heights of the walked line. Each cell may sit up to PROFILE_RANGE blocks off its ground (g, which never changes
     * by more than one between neighbours), never more than one block from the next, chosen by dynamic programming to
     * spend the least on stairs and earthwork: little bumps are cut, little dips filled, and the steps of a terraced
     * slope are spaced out instead of climbing in a flight. Fixed cells (decks, tunnels) keep their height; no step next
     * to a corner (a stair at a bend faces a wall). Then every one-block rise gets a step in its upper cell: a slab
     * when the rise stands alone, a stair facing up the slope in a flight or on a deck. dir[i] is the move into cell i, dir[n] the move into the road.
     */
    static Plan plan(int[] g, boolean[] fixed, boolean[] wet, int startY, int roadY, int[] dir) {
        int n = g.length, r = PROFILE_RANGE, s = 2 * r + 1;
        boolean[] corner = new boolean[n];
        for (int i = 0; i < n; i++) corner[i] = dir[i] != dir[i + 1];
        long inf = Long.MAX_VALUE / 4;
        long[][] cost = new long[n][2 * s];
        int[][] from = new int[n][2 * s];
        for (long[] row : cost) java.util.Arrays.fill(row, inf);
        for (int o = 0; o < s; o++) {
            if (fixed[0] && o != r) continue;
            int y = g[0] + o - r;
            if (Math.abs(y - startY) > 1) continue;
            int st = y != startY ? 1 : 0;
            cost[0][2 * o + st] = earth(o - r) + (st == 1 ? W_STEP + (corner[0] ? W_CORNER : 0) : 0);
        }
        for (int i = 1; i < n; i++)
            for (int p = 0; p < 2 * s; p++) {
                if (cost[i - 1][p] >= inf) continue;
                int py = g[i - 1] + p / 2 - r;
                for (int o = 0; o < s; o++) {
                    if (fixed[i] && o != r) continue;
                    int y = g[i] + o - r;
                    if (Math.abs(y - py) > 1) continue;
                    int st = y != py ? 1 : 0;
                    long c = cost[i - 1][p] + earth(o - r);
                    if (st == 1) c += W_STEP + (p % 2 == 1 ? W_FLIGHT : 0) + (corner[i] || corner[i - 1] ? W_CORNER : 0);
                    if (c < cost[i][2 * o + st]) {
                        cost[i][2 * o + st] = c;
                        from[i][2 * o + st] = p;
                    }
                }
            }
        int best = -1;
        long bestCost = inf;
        for (int q = 0; q < 2 * s; q++) {
            if (cost[n - 1][q] >= inf) continue;
            int y = g[n - 1] + q / 2 - r;
            if (Math.abs(y - roadY) > 1) continue;
            long c = cost[n - 1][q] + (y != roadY ? W_STEP + (corner[n - 1] ? W_CORNER : 0) : 0);
            if (c < bestCost) {
                bestCost = c;
                best = q;
            }
        }
        int[] y = g.clone();
        if (best >= 0)
            for (int i = n - 1, q = best; i >= 0; q = from[i][q], i--) y[i] = g[i] + q / 2 - r;
        int[] stair = new int[n];
        for (int i = 0; i < n; i++) {
            stair[i] = -1;
            int p = i == 0 ? startY : y[i - 1], q = i == n - 1 ? roadY : y[i + 1];
            if (y[i] == p + 1) stair[i] = dir[i];
            else if (y[i] == q + 1) stair[i] = (dir[i + 1] + 2) & 3;
        }
        // a rise on its own is a half step: a bottom slab in the upper cell, so the walk climbs half a block twice. Only a
        // flight (the cell beyond is higher still, or the cell below is itself a stair) keeps stairs: there a slab would
        // leave a block and a half to climb.
        boolean[] slab = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (stair[i] < 0 || wet[i]) continue;
            int p = i == 0 ? startY : y[i - 1], q = i == n - 1 ? roadY : y[i + 1];
            boolean pStair = i > 0 && y[i - 1] == y[i] - 1 && stair[i - 1] >= 0, qStair = i < n - 1 && y[i + 1] == y[i] - 1 && stair[i + 1] >= 0;
            if (p <= y[i] && q <= y[i] && !pStair && !qStair) slab[i] = true;
        }
        for (int i = 0; i < n; i++) if (slab[i]) stair[i] = -1;
        return new Plan(y, stair, slab);
    }

    private static long earth(int off) {
        return off < 0 ? (long) -off * W_CUT : (long) off * W_FILL;
    }

    /** The finished survey for a route (door end first, as the solver's legs give it), or null when the stake is already on the road. */
    static HousePaths.Survey build(Terrain t, int sx, int sz, int stakeY, List<long[]> acc, long endKey) {
        int n = acc.size();
        if (n == 0) return null;
        int[] xs = new int[n], zs = new int[n], ty = new int[n], dir = new int[n + 1], tm = new int[n];
        boolean[] wet = new boolean[n], fixed = new boolean[n];
        Set<Long> used = new HashSet<>();
        int px = sx, pz = sz, lastLevel = stakeY - 1;
        for (int i = 0; i < n; i++) {
            long col = acc.get(i)[0];
            xs[i] = ChunkPos.getX(col);
            zs[i] = ChunkPos.getZ(col);
            tm[i] = (int) acc.get(i)[1];
            dir[i] = dirOf(xs[i] - px, zs[i] - pz);
            px = xs[i];
            pz = zs[i];
            if (tm[i] > 0) {
                ty[i] = lastLevel;
                fixed[i] = true;
            } else {
                int g = t.ground(xs[i], zs[i]);
                if (Terrain.deep(g)) g = g - Terrain.DEEP + Terrain.WET;
                wet[i] = g >= Terrain.WET;
                ty[i] = Terrain.level(g);
                lastLevel = ty[i];
                fixed[i] = wet[i];
            }
            used.add(col);
        }
        dir[n] = dirOf(ChunkPos.getX(endKey) - px, ChunkPos.getZ(endKey) - pz);
        // a sandbar of up to BRIDGE_GAP cells between two stretches of water is bridged over too: one bridge, not two
        // with a jog between them (user, 0.1.11)
        for (int i = 1; i < n; i++) {
            if (wet[i] || !wet[i - 1] || tm[i] > 0) continue;
            int e = i;
            while (e < n && !wet[e] && tm[e] <= 0 && e - i < BRIDGE_GAP) e++;
            if (e >= n || !wet[e] || tm[e] > 0) continue;
            int deckY = Math.max(ty[i - 1], ty[e]);
            boolean low = true;
            for (int q = i; q < e; q++) if (ty[q] > deckY) low = false;
            if (!low) continue;
            for (int q = i; q < e; q++) {
                wet[q] = true;
                fixed[q] = true;
                ty[q] = deckY;
            }
            i = e;
        }
        // a crossing is one bridge: its deck at one height (the highest water level under it), numbered from the shore
        // for the posts
        int[] bidx = new int[n], blen = new int[n];
        for (int i = 0; i < n; ) {
            if (!wet[i] || tm[i] > 0) {
                i++;
                continue;
            }
            int a = i, top = Integer.MIN_VALUE;
            while (i < n && wet[i] && tm[i] <= 0) top = Math.max(top, ty[i++]);
            for (int q = a; q < i; q++) {
                ty[q] = top;
                bidx[q] = q - a;
                blen[q] = i - a;
            }
        }
        // a long crossing arches: half a block per cell from each shore, up to ARCH_MAX halves (a slab, then a plank a
        // block up with the water open under it)
        int[] arch = new int[n];
        for (int i = 0; i < n; i++) {
            if (!wet[i] || tm[i] > 0 || blen[i] < ARCH_MIN) continue;
            int top = blen[i] >= ARCH_HIGH ? ARCH_MAX : 2;
            arch[i] = Math.min(top, Math.min(bidx[i], blen[i] - 1 - bidx[i]));
        }
        // a crossing of KIT_MIN or more cells gets the user's bridge (BridgeKit): sized to cover the water with land at
        // both ends, on a straight stretch of the line; the cells under it follow the kit's deck heights. Anything else
        // keeps the plank deck.
        List<BridgeKit.Bridge> bridges = new ArrayList<>();
        List<int[]> misses = new ArrayList<>();
        int fordFill = 0;
        int[] kitAt = new int[n], kitH = new int[n];
        java.util.Arrays.fill(kitAt, -1);
        if (BridgeKit.available())
            for (int i = 0; i < n; ) {
                if (!wet[i] || tm[i] > 0) {
                    i++;
                    continue;
                }
                int a = i;
                while (i < n && wet[i] && tm[i] <= 0) i++;
                int w = i - a;
                if (w < KIT_MIN) {
                    // a creek of one or two cells: a ford (the water filled under the path), unless it is deep
                    boolean shallow = true;
                    for (int q = a; q < i; q++) if (Terrain.deep(t.ground(xs[q], zs[q]))) shallow = false;
                    if (shallow) {
                        for (int q = a; q < i; q++) {
                            wet[q] = false;
                            ty[q] -= 1;
                            fordFill += 3;
                        }
                        continue;
                    }
                }
                // every size from the smallest that covers the water, every placement along the line that still has a
                // land cell at both ends, nearest the middle first: the first that fits a straight stretch wins
                int s0 = -1, e0 = -1, k = -1, d = dir[a];
                String why = "no straight stretch with land at both ends";
                search:
                for (int kk = Math.max(BridgeKit.MIN_SPANS, (int) Math.ceil((w + 2 - BridgeKit.length(0)) / (double) BridgeKit.SPAN)); kk <= BridgeKit.MAX_SPANS; kk++) {
                    int len = BridgeKit.length(kk), lo = i - len + 1, hi = a - 1, mid = a - (len - w + 1) / 2;
                    for (int tr = 0; tr <= 2 * (hi - lo) + 1; tr++) {
                        int ss = mid + ((tr & 1) == 0 ? -(tr / 2) : (tr + 1) / 2);
                        if (ss < lo || ss > hi) continue;
                        if (ss < 1 || ss + len - 1 > n - 2) {
                            why = "the line is too short around the water (door or road too close)";
                            continue;
                        }
                        int ee = ss + len - 1;
                        if (wet[ss] || wet[ee]) {
                            why = "an end would stand in water";
                            continue;
                        }
                        boolean ok = dir[ss] == d && dir[ee + 1] == d;
                        for (int q = ss; q <= ee && ok; q++) if (dir[q] != d || tm[q] > 0 || kitAt[q] >= 0) ok = false;
                        if (!ok) {
                            why = "the line bends within " + len + " cells around the water";
                            continue;
                        }
                        s0 = ss;
                        e0 = ss + len - 1;
                        k = kk;
                        break search;
                    }
                }
                if (k < 0) {
                    if (w > BridgeKit.length(BridgeKit.MAX_SPANS) - 2) why = "longer than the longest kit bridge";
                    misses.add(new int[]{xs[a], zs[a], xs[i - 1], zs[i - 1], dir[a], w});
                    CountryRoads.LOG.info("CR-KIT miss at " + xs[a] + "," + zs[a] + ": " + w + " cells of water, " + why);
                    continue;
                }
                int y0 = Math.max(Math.max(ty[s0], ty[e0]) + 1, ty[a]);
                CountryRoads.LOG.info("CR-KIT bridge at " + xs[s0] + "," + zs[s0] + " spans=" + k + " length=" + BridgeKit.length(k) + " water=" + w + " y0=" + y0);
                bridges.add(new BridgeKit.Bridge(xs[s0], zs[s0], d, k, y0));
                // the cells just off both ends meet the kit at its own level: a step there would land on the kit's end
                // cell, which has no step block (the step happens one cell further out, as a slab like any other)
                for (int q : new int[]{s0 - 1, e0 + 1})
                    if (q >= 0 && q < n && tm[q] <= 0 && !wet[q] && Math.abs(ty[q] - (y0 - 1)) <= PROFILE_RANGE) {
                        ty[q] = y0 - 1;
                        fixed[q] = true;
                    }
                for (int q = s0; q <= e0; q++) {
                    kitAt[q] = bridges.size() - 1;
                    kitH[q] = BridgeKit.halves(k, q - s0);
                    ty[q] = y0 - 1 + kitH[q] / 2;
                    fixed[q] = true;
                    wet[q] = false;
                }
            }
        // near a bore the walked line keeps its ground: the portals need it, and a stretch passing over the tunnel must
        // not sink into its headroom
        Set<Long> bored = new HashSet<>();
        for (int i = 0; i < n; i++)
            if (tm[i] > 0)
                for (int dx = -3; dx <= 3; dx++)
                    for (int dz = -3; dz <= 3; dz++) bored.add(ChunkPos.pack(xs[i] + dx, zs[i] + dz));
        for (int i = 0; i < n; i++) if (bored.contains(ChunkPos.pack(xs[i], zs[i]))) fixed[i] = true;
        int roadY = Terrain.level(t.ground(ChunkPos.getX(endKey), ChunkPos.getZ(endKey)));
        Plan plan = plan(ty, fixed, wet, stakeY - 1, roadY, dir);

        // tunnel cells: open cut where the ground is not high enough to roof, portals at the first and last roofed cell
        int[] tun = new int[n], tidx = new int[n];
        java.util.Arrays.fill(tun, -1);
        for (int i = 0; i < n; ) {
            if (tm[i] <= 0) {
                i++;
                continue;
            }
            int a = i;
            while (i < n && tm[i] > 0 && (i == a || tm[i] == tm[i - 1] + 1)) i++;
            int first = -1, last = -1, run = 0;
            for (int q = a; q < i; q++) {
                boolean roofed = t.ground(xs[q], zs[q]) >= plan.y[q] + Tunnels.ROOF;
                tun[q] = roofed ? 1 : 0;
                if (roofed) {
                    tidx[q] = run++;
                    if (first < 0) first = q;
                    last = q;
                }
            }
            if (first >= 0) {
                tun[first] = 2;
                tun[last] = 2;
            }
        }

        long seed = RouteSolver.key(sx, sz);
        long doorKey = ChunkPos.pack(sx, sz);
        int surface = 0, earthFixed = 0, planks = 0, torches = 0, tunnelCells = 0, cut = 0, fill = 0, stoneExtra = 0, lanterns = 0;
        List<List<HousePaths.Cell>> bands = new ArrayList<>();
        // the tunnel's cross-section (rock, frames, portal posts two out) is shaped by Tunnels: no bed there, and no bank
        // within a block of it; an approach or an open cut beside it is ordinary path, three wide
        Set<Long> rock = new HashSet<>(), nearRock = new HashSet<>();
        for (int i = 0; i < n; i++) {
            bands.add(new ArrayList<>());
            if (tun[i] >= 0) {
                earthFixed += 3;
                tunnelCells++;
                if (tun[i] == 2) earthFixed += 11;
                if (tun[i] == 1 && tidx[i] % Tunnels.FRAME_EVERY == 4) {
                    planks += 11;
                    torches++;
                }
                int rx = -DZ[dir[i]], rz = DX[dir[i]];
                for (int s = -1; s <= 1; s += 2) {
                    int qx = xs[i] + s * rx, qz = zs[i] + s * rz;
                    if (used.contains(ChunkPos.pack(qx, qz)) || (qx == sx && qz == sz)) continue;
                    bands.get(i).add(new HousePaths.Cell(qx, qz, plan.y[i], false, true, plan.stair[i], dir[i], false, tun[i], tidx[i], false, plan.slab[i], false, 0, false));
                    used.add(ChunkPos.pack(qx, qz));
                }
                if (tun[i] >= 1)
                    for (int l = -2; l <= 2; l++) {
                        int qx = xs[i] + l * rx, qz = zs[i] + l * rz;
                        rock.add(ChunkPos.pack(qx, qz));
                        for (int dx = -1; dx <= 1; dx++)
                            for (int dz = -1; dz <= 1; dz++) nearRock.add(ChunkPos.pack(qx + dx, qz + dz));
                    }
            } else if (kitAt[i] >= 0) {
                // billed with the bridge
            } else if (wet[i]) {
                planks++;
            } else {
                surface++;
            }
        }

        // the bed: every column within a block of the walked line, at the height of its nearest centre cell, so the
        // path is three wide everywhere; it is cut into the slope or filled up to the path (at most BED_MAX)
        java.util.Map<Long, String> skipped = new java.util.HashMap<>();
        Long2IntOpenHashMap ref = new Long2IntOpenHashMap(), refMan = new Long2IntOpenHashMap();
        ref.defaultReturnValue(-1);
        // a kit bridge owns its whole footprint: its deck sides are cells of the bridge, nothing else shapes it
        for (BridgeKit.Bridge br : bridges)
            for (int z = 0; z < br.length(); z++)
                for (int x = 0; x < BridgeKit.WIDTH; x++) {
                    BlockPosLike q = kitColumn(br, x, z);
                    used.add(ChunkPos.pack(q.x(), q.z()));
                }
        for (int i = 0; i < n; i++) {
            if (tun[i] >= 0 || tm[i] > 0 || kitAt[i] >= 0) continue;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    long col = ChunkPos.pack(xs[i] + dx, zs[i] + dz);
                    if (dx == 0 && dz == 0 || used.contains(col)) continue;
                    if (rock.contains(col)) {
                        skipped.putIfAbsent(col, "tunnelRock");
                        continue;
                    }
                    int man = Math.abs(dx) + Math.abs(dz), had = ref.get(col);
                    if (had < 0 || man < refMan.get(col)) {
                        ref.put(col, i);
                        refMan.put(col, man);
                    }
                }
        }
        Long2IntOpenHashMap height = new Long2IntOpenHashMap();
        for (int i = 0; i < n; i++) height.put(ChunkPos.pack(xs[i], zs[i]), plan.y[i]);
        for (int i = 0; i < n; i++) {
            if (kitAt[i] >= 0) {
                BridgeKit.Bridge br = bridges.get(kitAt[i]);
                int z = Math.abs(xs[i] - br.x()) + Math.abs(zs[i] - br.z());
                for (int x = BridgeKit.MID - 1; x <= BridgeKit.MID + 1; x += 2) {
                    BlockPosLike q = kitColumn(br, x, z);
                    bands.get(i).add(new HousePaths.Cell(q.x(), q.z(), br.y0() - 1, false, true, -1, dir[i], false, -1, kitAt[i], false, false, false, kitH[i], true));
                    height.put(ChunkPos.pack(q.x(), q.z()), br.y0() - 1);
                }
                height.put(ChunkPos.pack(xs[i], zs[i]), br.y0() - 1);
            }
        }
        List<long[]> bedCols = new ArrayList<>();
        for (var e : ref.long2IntEntrySet()) bedCols.add(new long[]{e.getIntValue(), e.getLongKey()});
        bedCols.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        for (long[] bc : bedCols) {
            int i = (int) bc[0];
            long col = bc[1];
            int cx = ChunkPos.getX(col), cz = ChunkPos.getZ(col), ox = cx - xs[i], oz = cz - zs[i];
            if (col == doorKey || t.road(cx, cz)) {
                skipped.put(col, "road");
                continue;
            }
            int y = plan.y[i], stair = -1;
            if (plan.stair[i] >= 0) {
                int f = plan.stair[i];
                // the stair runs across the whole bed; a column off the stair's line keeps its ground
                if (refMan.get(col) != 1 || ox * DX[f] + oz * DZ[f] != 0) {
                    skipped.put(col, "stairOffLine");
                    continue;
                }
                stair = f;
            }
            int g = t.ground(cx, cz);
            // deep water beside a path on the shore: a deck at path height when the water lies just under it
            boolean deckDeep = Terrain.deep(g) && Math.abs(y - (g - Terrain.DEEP + 1)) <= 1;
            if (deckDeep) g = y - 1 + Terrain.WET;
            if (!Terrain.usable(g)) {
                skipped.put(col, Terrain.deep(g) ? "deep" + (g - Terrain.DEEP - y) : "closed");
                continue;
            }
            boolean sideWet = g >= Terrain.WET;
            int lvl = Terrain.level(g), extra = 0;
            if (deckDeep) {
                // too deep to fill: planks on the water
            } else if (sideWet && !wet[i]) {
                // shallow water beside a path on land: filled up to the path (its depth is not known here: billed as two)
                int water = lvl - 1;
                if (y < water || y - water > BED_MAX) {
                    skipped.put(col, "water" + (y - water));
                    continue;
                }
                sideWet = false;
                lvl = water;
                extra = 2;
            }
            if (sideWet ? Math.abs(lvl - y) > 1 : Math.abs(lvl - y) > BED_MAX) {
                skipped.put(col, sideWet ? "deckLevel" : "bedMax" + (lvl - y));
                continue;
            }
            fill += extra;
            bands.get(i).add(new HousePaths.Cell(cx, cz, y, sideWet, true, stair, dir[i], false, -1, 0, false, plan.slab[i] && !sideWet, false, sideWet ? arch[i] : 0, false));
            used.add(col);
            height.put(col, y);
            if (sideWet) planks++;
            else {
                surface++;
                if (lvl > y) cut += lvl - y;
                else fill += y - lvl;
            }
        }

        // bridge rails: two out from the walked line over the water, a stripped-log beam with a fence on it, where the
        // deck beside is laid and the column is water or low ground. Both sides together, on the rows of a straight
        // stretch where both can take a rail, so the two rails start, end and carry their posts opposite each other
        // (user, 0.1.12: posts did not line up): posts at both ends and evenly about every BRIDGE_POST cells between,
        // lanterns on the end posts where the bridge meets the shore; a run of one is a pair of posts
        for (int i = 0; i < n; ) {
            if (!railable(t, i, -1, wet, tm, tun, dir, xs, zs, plan.y, used, height, doorKey) || !railable(t, i, 1, wet, tm, tun, dir, xs, zs, plan.y, used, height, doorKey)) {
                i++;
                continue;
            }
            int a = i;
            i++;
            while (i < n && dir[i] == dir[i - 1] && railable(t, i, -1, wet, tm, tun, dir, xs, zs, plan.y, used, height, doorKey)
                && railable(t, i, 1, wet, tm, tun, dir, xs, zs, plan.y, used, height, doorKey)) i++;
            int len = i - a, gaps = Math.max(1, Math.round((len - 1) / (float) BRIDGE_POST));
            Set<Integer> posts = new HashSet<>();
            for (int j = 0; j <= gaps; j++) posts.add(a + Math.round(j * (len - 1) / (float) gaps));
            int rx = -DZ[dir[a]], rz = DX[dir[a]];
            boolean shoreA = a == 0 || !wet[a - 1] || shoreAt(t, xs[a] - DX[dir[a]] + 2 * rx, zs[a] - DZ[dir[a]] + 2 * rz, plan.y[a], false)
                || shoreAt(t, xs[a] - DX[dir[a]] - 2 * rx, zs[a] - DZ[dir[a]] - 2 * rz, plan.y[a], false);
            int b = i - 1;
            boolean shoreB = b == n - 1 || !wet[b + 1] || shoreAt(t, xs[b] + DX[dir[b]] + 2 * rx, zs[b] + DZ[dir[b]] + 2 * rz, plan.y[b], false)
                || shoreAt(t, xs[b] + DX[dir[b]] - 2 * rx, zs[b] + DZ[dir[b]] - 2 * rz, plan.y[b], false);
            for (int k = a; k < i; k++)
                for (int s = -1; s <= 1; s += 2) {
                    int qx = xs[k] + 2 * s * rx, qz = zs[k] + 2 * s * rz;
                    boolean post = posts.contains(k), lantern = Palette.bridgeDetail("lantern") && (k == a && shoreA || k == b && shoreB);
                    bands.get(k).add(new HousePaths.Cell(qx, qz, plan.y[k], true, true, -1, dir[k], false, -1, lantern ? 2 : post ? 1 : 0, false, false, true, arch[k], false));
                    used.add(ChunkPos.pack(qx, qz));
                    // a beam, a fence and a trapdoor (4 + 2 + 3 planks), a post about three logs
                    planks += post ? 12 : 6 + (Palette.bridgeDetail("trapdoor") ? 3 : 0);
                    if (post && Palette.bridgeDetail("stone")) stoneExtra += 2;
                    if (lantern) lanterns++;
                }
        }

        // banks: beyond the bed the ground is brought to within one block per column of the path, so there is no wall
        // and no drop beside it (at most BANK_MAX of cut or fill; never under a trunk or built block); a few columns
        // level with the path get a stray path block, so the trail frays into the verge
        Set<Long> banked = new HashSet<>();
        for (int i = 0; i < n; i++) {
            if (tun[i] >= 0 || tm[i] > 0 || kitAt[i] >= 0) continue;
            int reach = 1 + BANK_REACH;
            for (int dx = -reach; dx <= reach; dx++)
                for (int dz = -reach; dz <= reach; dz++) {
                    int cx = xs[i] + dx, cz = zs[i] + dz;
                    long col = ChunkPos.pack(cx, cz);
                    if (used.contains(col) || banked.contains(col) || nearRock.contains(col) || col == doorKey) continue;
                    // the nearest path column (fewest columns away, then straight across) and its height
                    int away = Integer.MAX_VALUE, man = Integer.MAX_VALUE, yRef = 0;
                    for (int ax = -BANK_REACH; ax <= BANK_REACH; ax++)
                        for (int az = -BANK_REACH; az <= BANK_REACH; az++) {
                            long q = ChunkPos.pack(cx + ax, cz + az);
                            if (!used.contains(q) || !height.containsKey(q)) continue;
                            int cheb = Math.max(Math.abs(ax), Math.abs(az)), m = Math.abs(ax) + Math.abs(az);
                            if (cheb < away || (cheb == away && m < man)) {
                                away = cheb;
                                man = m;
                                yRef = height.get(q);
                            }
                        }
                    if (away == Integer.MAX_VALUE) continue;
                    int g = t.ground(cx, cz);
                    if (!Terrain.usable(g) || g >= Terrain.WET || t.road(cx, cz)) continue;
                    int lvl = Terrain.level(g);
                    int target = Math.max(yRef - away, Math.min(yRef + away, lvl));
                    if (target == lvl) {
                        banked.add(col);
                        if (away == 1 && lvl == yRef && RouteSolver.lattice(seed + 9, cx, cz) < 0.22 && t.treeCost(cx, cz, lvl) == 0) {
                            bands.get(i).add(new HousePaths.Cell(cx, cz, lvl, false, true, -1, dir[i], true, -1, 0, false, false, false, 0, false));
                            used.add(col);
                        }
                        continue;
                    }
                    if (Math.abs(target - lvl) > BANK_MAX) target = lvl + Integer.signum(target - lvl) * BANK_MAX;
                    if (target < lvl && trunkOrBuiltNear(t, cx, cz)) continue;
                    banked.add(col);
                    bands.get(i).add(new HousePaths.Cell(cx, cz, target, false, true, -1, dir[i], false, -1, 0, true, false, false, 0, false));
                    if (lvl > target) cut += lvl - target;
                    else fill += target - lvl;
                }
        }

        List<HousePaths.Cell> cells = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (kitAt[i] >= 0)
                cells.add(new HousePaths.Cell(xs[i], zs[i], bridges.get(kitAt[i]).y0() - 1, false, false, -1, dir[i], false, -1, kitAt[i], false, false, false, kitH[i], true));
            else
                cells.add(new HousePaths.Cell(xs[i], zs[i], plan.y[i], wet[i], false, plan.stair[i], dir[i], false, tun[i], tidx[i], false, plan.slab[i] && !wet[i], false, arch[i], false));
            cells.addAll(bands.get(i));
        }
        // path blocks are half a block of material each; earth the cuts do not supply is paid; steps are paid in stone
        // (a stair is a block and a half of it, a slab half a block)
        int stairs = 0, slabs = 0;
        for (HousePaths.Cell c : cells) {
            if (c.water() || c.bank() || c.tun() >= 1) continue;
            if (c.stair() >= 0) stairs++;
            else if (c.slab()) slabs++;
        }
        surface -= stairs + slabs;
        int earth = (Math.max(0, surface) + 1) / 2 + earthFixed + Math.max(0, fill + fordFill - cut);
        int stone = 2 * stairs + (slabs + 1) / 2 + stoneExtra;
        for (BridgeKit.Bridge br : bridges) {
            int[] bill = BridgeKit.bill(br.spans());
            planks += bill[0];
            stone += bill[1];
            lanterns += bill[2];
        }
        // arched decks: a slab on every odd half, an upside-down stair under every rise of the underside (5 wide)
        for (int i = 0; i < n; i++) {
            if (arch[i] == 0) continue;
            planks += (arch[i] & 1) == 1 ? 2 : 0;
            if (i > 0 && arch[i] / 2 > arch[i - 1] / 2 || i < n - 1 && arch[i] / 2 > arch[i + 1] / 2) planks += 10;
        }
        return new HousePaths.Survey(cells, earth, stone, planks, torches, lanterns, tunnelCells, n, skipped, bridges, misses);
    }

    /** Can cell i of the walked line have a bridge rail on side s: a wet cell, its deck beside laid, the column open water or low ground. */
    private static boolean railable(Terrain t, int i, int s, boolean[] wet, int[] tm, int[] tun, int[] dir, int[] xs, int[] zs, int[] py,
                                    Set<Long> used, Long2IntOpenHashMap height, long doorKey) {
        if (!wet[i] || tm[i] > 0 || tun[i] >= 0) return false;
        int rx = -DZ[dir[i]], rz = DX[dir[i]];
        int qx = xs[i] + 2 * s * rx, qz = zs[i] + 2 * s * rz;
        long col = ChunkPos.pack(qx, qz), inner = ChunkPos.pack(xs[i] + s * rx, zs[i] + s * rz);
        if (used.contains(col) || col == doorKey || t.road(qx, qz) || !used.contains(inner) || height.get(inner) != py[i]) return false;
        int g = t.ground(qx, qz);
        return Terrain.deep(g) || Terrain.usable(g) && (g >= Terrain.WET || Terrain.level(g) <= py[i]);
    }

    /** Past the end of a rail run: land (the bridge reaches the shore there), or the walked line itself leaves the water. */
    private static boolean shoreAt(Terrain t, int x, int z, int y, boolean lineOnLand) {
        int g = t.ground(x, z);
        return lineOnLand || Terrain.usable(g) && g < Terrain.WET && Terrain.level(g) >= y - 1;
    }

    /** A trunk or something built within a block: a cut there would undercut it. */
    private static boolean trunkOrBuiltNear(Terrain t, int x, int z) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) if (t.ground(x + dx, z + dz) == Terrain.CLOSED) return true;
        return false;
    }
}
