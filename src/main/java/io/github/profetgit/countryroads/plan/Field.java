package io.github.profetgit.countryroads.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * A wheat field beside a plains road: one irregular blob (a deformed ellipse, RX along the road, RZ across it) centred
 * on the road. Everything here is a pure function of the field's parameters and world coordinates, so every chunk
 * agrees; road cuts, ground tests and village/prop exclusions are applied by the painter per column.
 */
public final class Field {
    public static final double RX = 30, RZ = 21;
    /** Chunks whose box is within this of the centre may hold part of the field (the blob reaches 1.5 * RX along the road). */
    public static final double REACH = 58;
    public static final double DENSITY = Double.parseDouble(System.getProperty("countryroads.fieldDensity", "1"));
    /** Fields keep this far apart (centre to centre). */
    public static final double APART = Math.max(70, 90 / DENSITY);
    private static final double RAG = 1.6 / RZ;

    public final double fx, fz, tx, tz;
    public final int y;
    public final long seed;
    /** Scarecrow pole column, arm direction (along the road axis), facing (toward the road); hasScarecrow false when none. */
    public final boolean hasScarecrow;
    public final int sx, sz, armX, armZ, faceX, faceZ;
    private final double[] phase = new double[8];
    private final int pocketX, pocketZ;
    private final float[] roadX, roadZ, roadHw;
    private volatile Rim rim;

    public Field(double fx, double fz, double tx, double tz, int y, long seed, int[] scarecrow, float[] roadX, float[] roadZ, float[] roadHw) {
        this.fx = fx;
        this.fz = fz;
        this.tx = tx;
        this.tz = tz;
        this.y = y;
        this.seed = seed;
        for (int k : new int[]{2, 3, 5, 7}) phase[k] = u(seed, 100 + k, 0, 0) * 2 * Math.PI;
        pocketX = (int) (u(seed, 120, 0, 0) * 9);
        pocketZ = (int) (u(seed, 121, 0, 0) * 9);
        this.hasScarecrow = scarecrow != null;
        this.sx = scarecrow == null ? 0 : scarecrow[0];
        this.sz = scarecrow == null ? 0 : scarecrow[1];
        this.armX = scarecrow == null ? 0 : scarecrow[2];
        this.armZ = scarecrow == null ? 0 : scarecrow[3];
        this.faceX = scarecrow == null ? 0 : scarecrow[4];
        this.faceZ = scarecrow == null ? 0 : scarecrow[5];
        this.roadX = roadX;
        this.roadZ = roadZ;
        this.roadHw = roadHw;
    }

    public int cx() {
        return (int) Math.floor(fx);
    }

    public int cz() {
        return (int) Math.floor(fz);
    }

    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public static double u(long seed, int tag, int a, int b) {
        long h = mix(seed ^ (tag * 0x9E3779B97F4A7C15L) ^ mix(a * 0x632BE59BD9B4E019L + b * 0x2545F4914F6CDD1DL + 0x1234567L));
        return (h >>> 11) / (double) (1L << 53);
    }

    public boolean before(Field o) {
        if (cx() != o.cx()) return cx() < o.cx();
        if (cz() != o.cz()) return cz() < o.cz();
        return seed < o.seed;
    }

    private double radius(double theta) {
        double r = 1;
        r += 0.22 * Math.sin(2 * theta + phase[2]);
        r += 0.16 * Math.sin(3 * theta + phase[3]);
        r += 0.08 * Math.sin(5 * theta + phase[5]);
        r += 0.04 * Math.sin(7 * theta + phase[7]);
        return r;
    }

    private double theta(int x, int z) {
        double px = x + 0.5 - fx, pz = z + 0.5 - fz;
        return Math.atan2((-px * tz + pz * tx) / RZ, (px * tx + pz * tz) / RX);
    }

    /** Normalised distance to the blob's edge: <= 0 inside. */
    public double norm(int x, int z) {
        double px = x + 0.5 - fx, pz = z + 0.5 - fz;
        double dx = (px * tx + pz * tz) / RX, dz = (-px * tz + pz * tx) / RZ;
        return Math.hypot(dx, dz) - radius(Math.atan2(dz, dx));
    }

    /** World x, z of the point at normalised angle theta and the given fraction of the edge radius. */
    public double[] at(double theta, double scale) {
        double r = radius(theta) * scale, dx = Math.cos(theta) * r * RX, dz = Math.sin(theta) * r * RZ;
        return new double[]{fx + tx * dx - tz * dz, fz + tz * dx + tx * dz};
    }

    /** The blob grown by the given number of blocks (about: the radii differ along and across the road). */
    public boolean covers(double x, double z, double blocks) {
        return norm((int) Math.floor(x), (int) Math.floor(z)) <= blocks / RZ;
    }

    public boolean nearChunk(int cx, int cz) {
        double x0 = cx << 4, z0 = cz << 4;
        double dx = Math.max(Math.max(x0 - fx, fx - (x0 + 16)), 0), dz = Math.max(Math.max(z0 - fz, fz - (z0 + 16)), 0);
        return Math.hypot(dx, dz) <= REACH;
    }

    /** 0 outside the blob, 1 headland (the ragged outer ring, 70% of it), 2 field. */
    public int state(int x, int z) {
        double n = norm(x, z);
        if (n > 0) return 0;
        if (n > -RAG && u(seed, 1, x, z) < 0.7) return 1;
        return 2;
    }

    /** Headland decoration: 0 none, 1 short grass, 2 poppy, 3 oxeye daisy. */
    public int headland(int x, int z) {
        double r = u(seed, 2, x, z);
        return r < 0.25 ? 1 : r < 0.31 ? (r < 0.28 ? 2 : 3) : 0;
    }

    public boolean pocketCell(int x, int z) {
        return Math.floorMod(x - pocketX, 9) == 0 && Math.floorMod(z - pocketZ, 9) == 0;
    }

    public boolean lily(int x, int z) {
        return u(seed, 3, x, z) < 0.5;
    }

    /** One block back from the road edge here (per 3 blocks along the road and side), about 40% of the time. */
    public boolean pushedBack(int x, int z, int side, float rtx, float rtz) {
        int cell = (int) Math.floor(((x + 0.5) * rtx + (z + 0.5) * rtz) / 3.0);
        return u(seed, side > 0 ? 301 : 302, cell, 0) < 0.4;
    }

    private double patch(int x, int z) {
        int cell = 7, cx = Math.floorDiv(x, cell), cz = Math.floorDiv(z, cell);
        double fx = (x - cx * cell) / (double) cell, fz = (z - cz * cell) / (double) cell;
        double a = u(seed, 11, cx, cz) * (1 - fx) + u(seed, 11, cx + 1, cz) * fx;
        double b = u(seed, 11, cx, cz + 1) * (1 - fx) + u(seed, 11, cx + 1, cz + 1) * fx;
        return a * (1 - fz) + b * fz + (u(seed, 12, x, z) - 0.5) * 0.12;
    }

    public int wheatAge(int x, int z) {
        double p = patch(x, z), w = u(seed, 13, x, z);
        if (p < 0.6) return w < 0.85 ? 7 : 6;
        if (p < 0.78) return 4 + (int) (w * 3);
        return 2 + (int) (w * 2);
    }

    private boolean farFromOwnRoad(int x, int z) {
        for (int i = 0; i < roadX.length; i++) {
            double r = roadHw[i] + 6.5;
            double dx = roadX[i] - (x + 0.5), dz = roadZ[i] - (z + 0.5);
            if (dx * dx + dz * dz < r * r) return false;
        }
        return true;
    }

    public boolean fenceAt(int x, int z) {
        return rim().fences.contains(BlockPos.asLong(x, 0, z));
    }

    /** Hedge height at the column: 0 none, 1 or 2 leaves high. */
    public int hedgeAt(int x, int z) {
        return rim().hedges.getOrDefault(BlockPos.asLong(x, 0, z), 0);
    }

    /** Fence posts of the field (tests). */
    public Set<Long> fenceCells() {
        return rim().fences;
    }

    private record Rim(Set<Long> fences, Map<Long, Integer> hedges) {}

    private Rim rim() {
        Rim r = rim;
        if (r == null) {
            synchronized (this) {
                if (rim == null) rim = buildRim();
                r = rim;
            }
        }
        return r;
    }

    private record Cell(int x, int z, double angle) {}

    private Rim buildRim() {
        int x0 = (int) Math.floor(fx - REACH), x1 = (int) Math.ceil(fx + REACH), z0 = (int) Math.floor(fz - REACH), z1 = (int) Math.ceil(fz + REACH);
        int w = x1 - x0 + 3, h = z1 - z0 + 3;
        boolean[] fld = new boolean[w * h];
        for (int x = x0 - 1; x <= x1 + 1; x++)
            for (int z = z0 - 1; z <= z1 + 1; z++) fld[(x - x0 + 1) * h + (z - z0 + 1)] = state(x, z) == 2;
        Map<Long, Cell> rimCells = new HashMap<>();
        List<Cell> all = new ArrayList<>();
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                int i = (x - x0 + 1) * h + (z - z0 + 1);
                if (fld[i] || !(fld[i - h] || fld[i + h] || fld[i - 1] || fld[i + 1])) continue;
                if (!farFromOwnRoad(x, z)) continue;
                Cell c = new Cell(x, z, theta(x, z));
                rimCells.put(BlockPos.asLong(x, 0, z), c);
                all.add(c);
            }
        Set<Long> raw = new HashSet<>();
        double t0 = u(seed, 130, 0, 0) * 2 * Math.PI;
        fragments(all, rimCells, 5, t0, 5, 8, 140, raw, true);
        // 2x2 squares: the lexicographically smallest post of a complete square is dropped
        Set<Long> fences = new HashSet<>();
        for (long k : raw) {
            int x = BlockPos.getX(k), z = BlockPos.getZ(k);
            boolean drop = false;
            for (int sx = -1; sx <= 0 && !drop; sx++)
                for (int sz = -1; sz <= 0 && !drop; sz++) {
                    int ax = x + sx, az = z + sz;
                    if (!raw.contains(BlockPos.asLong(ax, 0, az)) || !raw.contains(BlockPos.asLong(ax + 1, 0, az)) || !raw.contains(BlockPos.asLong(ax, 0, az + 1))
                        || !raw.contains(BlockPos.asLong(ax + 1, 0, az + 1))) continue;
                    drop = x == ax && z == az;
                }
            if (!drop) fences.add(k);
        }
        Set<Long> hedgeRaw = new HashSet<>();
        fragments(all, rimCells, 9, u(seed, 131, 0, 0) * 2 * Math.PI, 2, 4, 160, hedgeRaw, false);
        Map<Long, Integer> hedges = new HashMap<>();
        for (long k : hedgeRaw) {
            int x = BlockPos.getX(k), z = BlockPos.getZ(k);
            int ox = x + 0.5 > fx ? 1 : -1, oz = z + 0.5 > fz ? 1 : -1;
            int[][] cells = {{x, z, 1}, {x + ox, z, 0}, {x, z + oz, 0}};
            for (int[] c : cells) {
                long ck = BlockPos.asLong(c[0], 0, c[1]);
                if (fences.contains(ck) || state(c[0], c[1]) == 2 || !farFromOwnRoad(c[0], c[1])) continue;
                if (c[2] == 0 && u(seed, 150, c[0], c[1]) >= 0.8) continue;
                hedges.put(ck, u(seed, 151, c[0], c[1]) < 0.3 ? 2 : 1);
            }
        }
        return new Rim(fences, hedges);
    }

    /**
     * One fragment per angle bucket: a walk of min..min+span-1 rim columns (a few more for hedges, 1 + the draw) from a
     * start chosen inside the bucket, stepping to the next rim column round the field (8-neighbours, smallest positive
     * angle step); diagonal steps get their corner so a fence line stays joined.
     */
    private void fragments(List<Cell> all, Map<Long, Cell> rimCells, int buckets, double theta0, int min, int span, int tag, Set<Long> out, boolean corners) {
        List<List<Cell>> by = new ArrayList<>();
        for (int b = 0; b < buckets; b++) by.add(new ArrayList<>());
        for (Cell c : all) {
            double a = Math.floorMod((long) 0, 1) + (c.angle - theta0);
            a = a - Math.floor(a / (2 * Math.PI)) * 2 * Math.PI;
            by.get(Math.min(buckets - 1, (int) (a / (2 * Math.PI) * buckets))).add(c);
        }
        for (int b = 0; b < buckets; b++) {
            List<Cell> list = by.get(b);
            if (list.isEmpty()) continue;
            list.sort((p, q) -> p.x != q.x ? Integer.compare(p.x, q.x) : Integer.compare(p.z, q.z));
            Cell cur = list.get((int) (u(seed, tag + b, 0, 0) * list.size()));
            int len = min + (int) (u(seed, tag + 20 + b, 0, 0) * span);
            Set<Long> walked = new HashSet<>();
            for (int step = 0; step < len && cur != null; step++) {
                long ck = BlockPos.asLong(cur.x, 0, cur.z);
                walked.add(ck);
                out.add(ck);
                Cell next = null;
                double best = Double.MAX_VALUE;
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        Cell n = rimCells.get(BlockPos.asLong(cur.x + dx, 0, cur.z + dz));
                        if (n == null || walked.contains(BlockPos.asLong(n.x, 0, n.z))) continue;
                        double d = n.angle - cur.angle;
                        d = d - Math.floor(d / (2 * Math.PI)) * 2 * Math.PI;
                        if (d > Math.PI) continue;
                        if (d < best) {
                            best = d;
                            next = n;
                        }
                    }
                if (next != null && corners && next.x != cur.x && next.z != cur.z) {
                    int[][] c = {{next.x, cur.z}, {cur.x, next.z}};
                    int pick = -1;
                    double far = -1e9;
                    for (int k = 0; k < 2; k++) {
                        if (state(c[k][0], c[k][1]) == 2) continue;
                        double n = norm(c[k][0], c[k][1]);
                        if (n > far) {
                            far = n;
                            pick = k;
                        }
                    }
                    if (pick >= 0 && farFromOwnRoad(c[pick][0], c[pick][1])) out.add(BlockPos.asLong(c[pick][0], 0, c[pick][1]));
                }
                cur = next;
            }
        }
    }
}
