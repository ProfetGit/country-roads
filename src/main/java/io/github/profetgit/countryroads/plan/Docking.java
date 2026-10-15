package io.github.profetgit.countryroads.plan;

import io.github.profetgit.countryroads.world.StreetBands;
import io.github.profetgit.countryroads.world.VillageOracle;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Where a road meets a village street: a street (band) column on the village's side toward the other village, and a
 * short connector from it out of the village over ground no house stands on. The road then starts at the connector's
 * far end, so it runs onto the street instead of stopping at the village's edge.
 */
final class Docking {
    private Docking() {}

    /** pts: block centres from the dock (a band column) out to the exit, one per block step. dryPick: chosen over a wetter, nearer one. */
    record Connector(List<int[]> cells, int dockX, int dockZ, int exitX, int exitZ, boolean dryPick) {
        Connector picked() {
            return new Connector(cells, dockX, dockZ, exitX, exitZ, true);
        }
    }

    /** The village's terrain shaping (beard) reaches about this far past its pieces; a connector's end must lie beyond it to match the terrain the planner estimates. */
    static final int BEARD = 12;
    private static final int MAX_STEPS = 56;
    private static final int TRIES = 24;

    /** Dry land a connector's exit needs ahead of it toward the partner village, so a crossing starts out there with room to land. */
    static final int APRON = 12;

    /**
     * Null when the village has no street columns or none of the best docks can reach open ground. With terrain, the
     * first connector that stays on dry land with an APRON of dry land ahead wins (a road leaving a riverside village
     * over the water put a stone bridge's end right against the street); when none does, the first one as before.
     */
    static Connector connect(VillageOracle.Footprint f, int partnerX, int partnerZ, io.github.profetgit.countryroads.world.TerrainSampler terrain) {
        StreetBands bands = f.bands();
        if (bands.isEmpty()) return toDoor(f, partnerX, partnerZ, terrain);
        List<int[]> cols = bands.columns();
        cols.sort(Comparator.comparingDouble((int[] c) -> Math.hypot(c[0] - partnerX, c[1] - partnerZ)).thenComparingInt(c -> c[0]).thenComparingInt(c -> c[1]));
        int tried = 0;
        Connector first = null;
        List<int[]> used = new ArrayList<>();
        for (int[] c : cols) {
            boolean near = false;
            for (int[] u : used) near |= Math.abs(u[0] - c[0]) + Math.abs(u[1] - c[1]) < 6;
            if (near) continue;
            used.add(c);
            Connector k = search(f, c[0], c[1], partnerX, partnerZ);
            if (k != null) {
                if (terrain == null || dry(k, terrain, partnerX, partnerZ)) {
                    if (first == null) return k;
                    io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-DOCK-DRY " + k.exitX() + "," + k.exitZ() + " instead of wet " + first.exitX() + "," + first.exitZ());
                    return k.picked();
                }
                if (first == null) first = k;
            }
            if (++tried >= TRIES) break;
        }
        if (first != null) {
            io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-DOCK-WET " + first.exitX() + "," + first.exitZ() + " (no dry exit toward " + partnerX + "," + partnerZ + ")");
            return first;
        }
        io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-DOCK-TRIED " + tried + " docks, nearest to partner at " + cols.getFirst()[0] + "," + cols.getFirst()[1] + " partner " + partnerX + "," + partnerZ);
        return null;
    }

    /**
     * A village without streets: the road comes up to a door. The door walk whose end is nearest the partner, then the
     * usual search on to open ground; a dry one first, as for streets. Null when there is no door (trader camp).
     */
    private static Connector toDoor(VillageOracle.Footprint f, int partnerX, int partnerZ, io.github.profetgit.countryroads.world.TerrainSampler terrain) {
        List<List<int[]>> walks = new ArrayList<>(f.doorWalks());
        if (walks.isEmpty()) return null;
        walks.sort(Comparator.comparingDouble((List<int[]> w) -> Math.hypot(w.getLast()[0] - partnerX, w.getLast()[1] - partnerZ))
            .thenComparingInt(w -> w.getFirst()[0]).thenComparingInt(w -> w.getFirst()[1]));
        Connector first = null;
        for (List<int[]> w : walks) {
            int[] end = w.getLast();
            Connector k = search(f, end[0], end[1], partnerX, partnerZ);
            if (k == null) continue;
            List<int[]> cells = new ArrayList<>(w.subList(0, w.size() - 1));
            cells.addAll(k.cells());
            Connector door = new Connector(cells, w.getFirst()[0], w.getFirst()[1], k.exitX(), k.exitZ(), false);
            if (terrain == null || dry(door, terrain, partnerX, partnerZ)) return first == null ? door : door.picked();
            if (first == null) first = door;
        }
        return first;
    }

    /** The connector's cells (every other one) and the APRON ahead of its exit toward the partner are all dry. */
    private static boolean dry(Connector k, io.github.profetgit.countryroads.world.TerrainSampler terrain, int partnerX, int partnerZ) {
        for (int i = 0; i < k.cells().size(); i += 2) {
            int[] c = k.cells().get(i);
            if (terrain.sample(c[0], c[1]).wet()) return false;
        }
        if (terrain.sample(k.exitX(), k.exitZ()).wet()) return false;
        double dx = partnerX - k.exitX(), dz = partnerZ - k.exitZ(), l = Math.max(1e-6, Math.hypot(dx, dz));
        for (int s = 3; s <= APRON; s += 3)
            if (terrain.sample((int) Math.floor(k.exitX() + 0.5 + dx / l * s), (int) Math.floor(k.exitZ() + 0.5 + dz / l * s)).wet()) return false;
        return true;
    }

    private static long key(int x, int z) {
        return StreetBands.pack(x, z);
    }

    /** Cheapest way from the dock to a column outside every piece box, leaning toward the partner village; never through a house. */
    private static Connector search(VillageOracle.Footprint f, int sx, int sz, int partnerX, int partnerZ) {
        double pdx = partnerX - sx, pdz = partnerZ - sz, pl = Math.max(1e-6, Math.hypot(pdx, pdz));
        pdx /= pl;
        pdz /= pl;
        Long2DoubleOpenHashMap dist = new Long2DoubleOpenHashMap();
        dist.defaultReturnValue(Double.MAX_VALUE);
        Long2LongOpenHashMap from = new Long2LongOpenHashMap();
        PriorityQueue<double[]> open = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        dist.put(key(sx, sz), 0);
        open.add(new double[]{0, sx, sz});
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        while (!open.isEmpty()) {
            double[] cur = open.poll();
            int x = (int) cur[1], z = (int) cur[2];
            long ck = key(x, z);
            if (cur[0] > dist.get(ck)) continue;
            if (!f.contains(x, z, BEARD) && !f.bands().contains(x, z)) return build(from, sx, sz, x, z);
            if (Math.abs(x - sx) > MAX_STEPS || Math.abs(z - sz) > MAX_STEPS) continue;
            for (int[] d : dirs) {
                int nx = x + d[0], nz = z + d[1];
                if (f.inOther(nx, nz, 0)) continue;
                boolean diag = d[0] != 0 && d[1] != 0;
                if (diag && (f.inOther(x + d[0], z, 0) || f.inOther(x, z + d[1], 0))) continue;
                double len = diag ? 1.4142 : 1.0;
                double lean = (d[0] * pdx + d[1] * pdz) / (diag ? 1.4142 : 1.0);
                double cost = len * (1.0 + 0.5 * (1 - lean)) + (f.inOther(nx, nz, 1) ? 4 : 0) + (f.bands().contains(nx, nz) ? 0 : 0.2);
                double nd = cur[0] + cost;
                long nk = key(nx, nz);
                if (nd < dist.get(nk)) {
                    dist.put(nk, nd);
                    from.put(nk, ck);
                    open.add(new double[]{nd, nx, nz});
                }
            }
        }
        return null;
    }

    private static Connector build(Long2LongOpenHashMap from, int sx, int sz, int ex, int ez) {
        List<int[]> rev = new ArrayList<>();
        long k = key(ex, ez), start = key(sx, sz);
        while (true) {
            rev.add(new int[]{(int) (k >> 32), (int) k});
            if (k == start) break;
            k = from.get(k);
        }
        List<int[]> cells = new ArrayList<>(rev.size());
        for (int i = rev.size() - 1; i >= 0; i--) cells.add(rev.get(i));
        return new Connector(cells, sx, sz, ex, ez, false);
    }
}
