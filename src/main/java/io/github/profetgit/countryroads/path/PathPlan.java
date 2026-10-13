package io.github.profetgit.countryroads.path;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
                wet[i] = g >= Terrain.WET;
                ty[i] = Terrain.level(g);
                lastLevel = ty[i];
                fixed[i] = wet[i];
            }
            used.add(col);
        }
        dir[n] = dirOf(ChunkPos.getX(endKey) - px, ChunkPos.getZ(endKey) - pz);
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
        int surface = 0, earthFixed = 0, planks = 0, torches = 0, tunnelCells = 0, cut = 0, fill = 0;
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
                    bands.get(i).add(new HousePaths.Cell(qx, qz, plan.y[i], false, true, plan.stair[i], dir[i], false, tun[i], tidx[i], false, plan.slab[i]));
                    used.add(ChunkPos.pack(qx, qz));
                }
                if (tun[i] >= 1)
                    for (int l = -2; l <= 2; l++) {
                        int qx = xs[i] + l * rx, qz = zs[i] + l * rz;
                        rock.add(ChunkPos.pack(qx, qz));
                        for (int dx = -1; dx <= 1; dx++)
                            for (int dz = -1; dz <= 1; dz++) nearRock.add(ChunkPos.pack(qx + dx, qz + dz));
                    }
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
        for (int i = 0; i < n; i++) {
            if (tun[i] >= 0 || tm[i] > 0) continue;
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
            if (!Terrain.usable(g)) {
                skipped.put(col, "closed");
                continue;
            }
            boolean sideWet = g >= Terrain.WET;
            int lvl = Terrain.level(g), extra = 0;
            if (sideWet && !wet[i]) {
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
            if (sideWet ? lvl != y : Math.abs(lvl - y) > BED_MAX) {
                skipped.put(col, sideWet ? "deckLevel" : "bedMax" + (lvl - y));
                continue;
            }
            fill += extra;
            bands.get(i).add(new HousePaths.Cell(cx, cz, y, sideWet, true, stair, dir[i], false, -1, 0, false, plan.slab[i] && !sideWet));
            used.add(col);
            height.put(col, y);
            if (sideWet) planks++;
            else {
                surface++;
                if (lvl > y) cut += lvl - y;
                else fill += y - lvl;
            }
        }

        // banks: beyond the bed the ground is brought to within one block per column of the path, so there is no wall
        // and no drop beside it (at most BANK_MAX of cut or fill; never under a trunk or built block); a few columns
        // level with the path get a stray path block, so the trail frays into the verge
        Set<Long> banked = new HashSet<>();
        for (int i = 0; i < n; i++) {
            if (tun[i] >= 0 || tm[i] > 0) continue;
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
                            bands.get(i).add(new HousePaths.Cell(cx, cz, lvl, false, true, -1, dir[i], true, -1, 0, false, false));
                            used.add(col);
                        }
                        continue;
                    }
                    if (Math.abs(target - lvl) > BANK_MAX) target = lvl + Integer.signum(target - lvl) * BANK_MAX;
                    if (target < lvl && trunkOrBuiltNear(t, cx, cz)) continue;
                    banked.add(col);
                    bands.get(i).add(new HousePaths.Cell(cx, cz, target, false, true, -1, dir[i], false, -1, 0, true, false));
                    if (lvl > target) cut += lvl - target;
                    else fill += target - lvl;
                }
        }

        List<HousePaths.Cell> cells = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            cells.add(new HousePaths.Cell(xs[i], zs[i], plan.y[i], wet[i], false, plan.stair[i], dir[i], false, tun[i], tidx[i], false, plan.slab[i] && !wet[i]));
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
        int earth = (Math.max(0, surface) + 1) / 2 + earthFixed + Math.max(0, fill - cut);
        int stone = 2 * stairs + (slabs + 1) / 2;
        return new HousePaths.Survey(cells, earth, stone, planks, torches, tunnelCells, n, skipped);
    }

    /** A trunk or something built within a block: a cut there would undercut it. */
    private static boolean trunkOrBuiltNear(Terrain t, int x, int z) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) if (t.ground(x + dx, z + dz) == Terrain.CLOSED) return true;
        return false;
    }
}
