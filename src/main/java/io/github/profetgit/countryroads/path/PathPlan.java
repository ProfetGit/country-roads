package io.github.profetgit.countryroads.path;

import io.github.profetgit.countryroads.world.RoadColumns;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;

/**
 * From the walked line to the cells to build: heights and stairs, the worn band around the line, tunnels, and the bill.
 */
final class PathPlan {
    private PathPlan() {}

    static final int[] DX = HousePaths.DX, DZ = HousePaths.DZ;

    /** The planned surface and stair facing of every centre cell. */
    record Plan(int[] y, int[] stair) {}

    static int dirOf(int dx, int dz) {
        for (int d = 0; d < 4; d++) if (DX[d] == dx && DZ[d] == dz) return d;
        return 0;
    }

    /**
     * Heights are whole blocks and never differ by more than one between neighbours (the search guarantees it). A cell
     * that is a lone bump or dip is levelled with its neighbours (not a fixed one: deck, tunnel), then every one-block
     * rise gets a stair in its upper cell, facing up the slope; a one-block drop onto or off the fixed ends (door, road)
     * stays a plain step. dir[i] is the move into cell i, dir[n] the move into the road.
     */
    static Plan plan(int[] ty, boolean[] fixed, int startY, int roadY, int[] dir) {
        int n = ty.length;
        int[] y = ty.clone(), stair = new int[n];
        for (int pass = 0; pass < 3; pass++)
            for (int i = 0; i < n; i++) {
                if (fixed[i]) continue;
                int p = i == 0 ? startY : y[i - 1], q = i == n - 1 ? roadY : y[i + 1];
                if (y[i] > p && y[i] > q) y[i] = Math.max(p, q);
                else if (y[i] < p && y[i] < q) y[i] = Math.min(p, q);
            }
        for (int i = 0; i < n; i++) {
            stair[i] = -1;
            int p = i == 0 ? startY : y[i - 1], q = i == n - 1 ? roadY : y[i + 1];
            if (y[i] == p + 1) stair[i] = dir[i];
            else if (y[i] == q + 1) stair[i] = (dir[i + 1] + 2) & 3;
        }
        return new Plan(y, stair);
    }

    /** The finished survey for a route (door end first, as PathSearch.extract gives it), or null when the stake is already on the road. */
    static HousePaths.Survey build(PathSearch j, List<long[]> acc, long endKey) {
        int n = acc.size();
        if (n == 0) return null;
        int[] xs = new int[n], zs = new int[n], ty = new int[n], dir = new int[n + 1], tm = new int[n];
        boolean[] wet = new boolean[n], fixed = new boolean[n];
        Set<Long> used = new HashSet<>();
        int px = j.sx, pz = j.sz, lastLevel = j.stake.getY() - 1;
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
                int g = j.ground(xs[i], zs[i]);
                wet[i] = g >= 100000;
                ty[i] = PathSearch.level(g);
                lastLevel = ty[i];
                fixed[i] = wet[i];
            }
            used.add(col);
        }
        dir[n] = dirOf(ChunkPos.getX(endKey) - px, ChunkPos.getZ(endKey) - pz);
        int roadY = PathSearch.level(j.ground(ChunkPos.getX(endKey), ChunkPos.getZ(endKey)));
        Plan plan = plan(ty, fixed, j.stake.getY() - 1, roadY, dir);

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
                boolean roofed = j.ground(xs[q], zs[q]) >= plan.y[q] + Tunnels.ROOF;
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

        long seed = PathSearch.key(j.sx, j.sz);
        int earth = 0, planks = 0, torches = 0, tunnelCells = 0;
        List<List<HousePaths.Cell>> bands = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            bands.add(new ArrayList<>());
            if (tun[i] >= 0) {
                earth += 3;
                tunnelCells++;
                if (tun[i] == 2) earth += 11;
                if (tun[i] == 1 && tidx[i] % Tunnels.FRAME_EVERY == 4) {
                    planks += 11;
                    torches++;
                }
                int rx = -DZ[dir[i]], rz = DX[dir[i]];
                for (int s = -1; s <= 1; s += 2) {
                    int qx = xs[i] + s * rx, qz = zs[i] + s * rz;
                    if (used.contains(ChunkPos.pack(qx, qz)) || (qx == j.sx && qz == j.sz)) continue;
                    bands.get(i).add(new HousePaths.Cell(qx, qz, plan.y[i], false, true, plan.stair[i], dir[i], false, tun[i], tidx[i]));
                    used.add(ChunkPos.pack(qx, qz));
                }
            } else if (wet[i]) {
                planks++;
            } else {
                earth++;
            }
        }
        // the worn band: a wandering offset and a changing radius around the walked line, ragged at the rim
        for (int i = 1; i + 1 < n; i++) {
            if (wet[i] || tun[i] >= 0 || tm[i] > 0) continue;
            int off = (int) Math.round((PathSearch.along(seed + 11, i) - 0.5) * 2.4);
            double rad = 0.35 + 1.55 * PathSearch.along(seed + 23, i);
            int rx = -DZ[dir[i]], rz = DX[dir[i]];
            for (int o = -3; o <= 3; o++) {
                if (o == 0) continue;
                int qx = xs[i] + o * rx, qz = zs[i] + o * rz;
                long qk = ChunkPos.pack(qx, qz);
                if (used.contains(qk) || (qx == j.sx && qz == j.sz)) continue;
                double jitter = (PathSearch.lattice(seed + 5, qx, qz) - 0.5) * 1.1;
                double d = Math.abs(o - off);
                boolean fringe = d > rad;
                if (d > rad + jitter) continue;
                if (fringe && PathSearch.lattice(seed + 9, qx, qz) > 0.5) continue;
                if (RoadColumns.isRoad(j.level, qx, qz)) continue;
                int sh = j.ground(qx, qz);
                if (sh < -1000 || sh >= 100000 || j.treeCost(qx, qz, plan.y[i]) > 0) continue;
                boolean steps = plan.stair[i] >= 0;
                if (steps ? fringe || Math.abs(sh - plan.y[i]) > 1 : sh != plan.y[i]) continue;
                // keep the band joined to the walked line: an outer cell needs the one inside it
                if (Math.abs(o) > 1 && !used.contains(ChunkPos.pack(xs[i] + (o - Integer.signum(o)) * rx, zs[i] + (o - Integer.signum(o)) * rz))) continue;
                bands.get(i).add(new HousePaths.Cell(qx, qz, plan.y[i], false, true, plan.stair[i], dir[i], fringe, -1, 0));
                used.add(qk);
                earth++;
            }
        }
        List<HousePaths.Cell> cells = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            cells.add(new HousePaths.Cell(xs[i], zs[i], plan.y[i], wet[i], false, plan.stair[i], dir[i], false, tun[i], tidx[i]));
            cells.addAll(bands.get(i));
        }
        return new HousePaths.Survey(cells, (earth + 1) / 2, planks, torches, tunnelCells, n);
    }
}
