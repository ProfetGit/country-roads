package io.github.profetgit.countryroads.path;

import io.github.profetgit.countryroads.world.TerrainSampler;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.PriorityQueue;
import net.minecraft.world.level.ChunkPos;

/**
 * Where the route will roughly go, before its chunks exist: an A* over 8-block cells, with real ground where chunks
 * are captured and the level generator's own terrain (TerrainSampler, sampled at chunk centres like the road router,
 * so road planning has often sampled them already) everywhere else. The fine search is then held to a corridor around
 * that line, so a survey far from loaded land generates only the chunks along the corridor, in one batch, instead of
 * everything the exact search would look at.
 */
final class CoarsePlanner {
    static final int CELL = 8;
    /** Cost of a cell step, roughly what the fine search pays for 8 blocks (base, jitter and wobble on average). */
    private static final float STEP = 8 * 21f, WET = 8 * 30f, TREES = 8 * 14f;
    /** Going up more than this in one cell is about what a bore through it costs. */
    private static final float CLIMB_CAP = 8 * 26f + 80;

    private final ColumnSnapshot.View view;
    private final TerrainSampler sampler;
    private final int c0x, c0z, n, sx, sz, radius;
    private final float[] height, wet, trees;
    private final byte[] state;
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<TerrainSampler.Sample> lattice = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    int samples;

    CoarsePlanner(ColumnSnapshot.View view, TerrainSampler sampler, int sx, int sz, int radius) {
        this.view = view;
        this.sampler = sampler;
        this.sx = sx;
        this.sz = sz;
        this.radius = radius;
        this.c0x = Math.floorDiv(sx - radius, CELL);
        this.c0z = Math.floorDiv(sz - radius, CELL);
        this.n = Math.floorDiv(sx + radius, CELL) - c0x + 1;
        this.height = new float[n * n];
        this.wet = new float[n * n];
        this.trees = new float[n * n];
        this.state = new byte[n * n];
    }

    private int cell(int x, int z) {
        return (Math.floorDiv(x, CELL) - c0x) * n + (Math.floorDiv(z, CELL) - c0z);
    }

    /** 0 unknown, 1 passable, 2 closed (deep water, lava, built-up). */
    private byte stats(int c) {
        if (state[c] != 0) return state[c];
        int ci = c / n + c0x, cj = c % n + c0z, bx = ci * CELL, bz = cj * CELL;
        ColumnSnapshot.Chunk ch = view.chunk(bx, bz);
        byte st;
        if (ch != null) {
            // captured: the real columns
            int usable = 0, wets = 0, tree = 0;
            int[] lv = new int[CELL * CELL];
            for (int x = bx; x < bx + CELL; x++)
                for (int z = bz; z < bz + CELL; z++) {
                    int g = view.ground(x, z);
                    if (!Terrain.usable(g)) continue;
                    lv[usable++] = Terrain.level(g);
                    if (g >= Terrain.WET) wets++;
                    if (ch.leaves()[((z & 15) << 4) | (x & 15)] != 0) tree++;
                }
            if (usable < CELL * CELL / 4) st = 2;
            else {
                Arrays.sort(lv, 0, usable);
                height[c] = lv[usable / 2];
                wet[c] = wets / (float) usable;
                trees[c] = tree / (float) usable;
                st = 1;
            }
        } else {
            // not generated (or not loaded): the generator's terrain at the surrounding chunk centres
            float fx = (bx + CELL / 2f - 8) / 16f, fz = (bz + CELL / 2f - 8) / 16f;
            int ix = (int) Math.floor(fx), iz = (int) Math.floor(fz);
            float tx = fx - ix, tz = fz - iz, h = 0, w = 0;
            boolean closed = false;
            for (int a = 0; a <= 1; a++)
                for (int b = 0; b <= 1; b++) {
                    TerrainSampler.Sample s = sample(ix + a, iz + b);
                    float k = (a == 0 ? 1 - tx : tx) * (b == 0 ? 1 - tz : tz);
                    h += k * (s.wet() ? s.water() + 1 : s.ground());
                    if (s.wet()) {
                        w += k;
                        if (s.depth() >= 6 && k >= 0.25f) closed = true;
                    }
                }
            height[c] = h;
            wet[c] = w;
            st = closed ? (byte) 2 : 1;
        }
        state[c] = st;
        return st;
    }

    private TerrainSampler.Sample sample(int i, int j) {
        long k = ChunkPos.pack(i, j);
        TerrainSampler.Sample s = lattice.get(k);
        if (s == null) {
            s = sampler.sample(i * 16 + 8, j * 16 + 8);
            lattice.put(k, s);
            samples++;
        }
        return s;
    }

    private float step(int a, int b, float[] penalty) {
        if (stats(b) == 2) return Float.POSITIVE_INFINITY;
        float dh = Math.abs(height[b] - height[a]);
        float climb = 8 * dh + Math.max(0, 3 * dh - CELL) * 21;
        float c = STEP + Math.min(climb, CLIMB_CAP) + wet[b] * WET + trees[b] * TREES;
        return penalty == null ? c : c * (1 + penalty[b]);
    }

    /**
     * The chunks of a corridor of the given half-width (cells) around the coarse routes from (fromX, fromZ) to the
     * nearest goal: the best one and routes - 1 more, each kept away from the earlier ones (cells near them cost half
     * as much again), so the fine search still chooses between real alternatives. Null when there is no coarse route.
     */
    LongOpenHashSet corridor(int fromX, int fromZ, Collection<Long> goals, int halfWidth, int routes) {
        LongOpenHashSet out = null;
        float[] penalty = new float[n * n];
        for (int r = 0; r < routes; r++) {
            int[] path = route(fromX, fromZ, goals, r == 0 ? null : penalty);
            if (path == null) break;
            if (out == null) out = new LongOpenHashSet();
            for (int c : path) {
                int ci = c / n + c0x, cj = c % n + c0z;
                for (int a = -halfWidth; a <= halfWidth; a++)
                    for (int b = -halfWidth; b <= halfWidth; b++) {
                        int x = (ci + a) * CELL, z = (cj + b) * CELL;
                        out.add(ChunkPos.pack(x >> 4, z >> 4));
                        int pi = ci + a - c0x, pj = cj + b - c0z;
                        if (Math.abs(a) <= 2 && Math.abs(b) <= 2 && pi >= 0 && pj >= 0 && pi < n && pj < n) penalty[pi * n + pj] = 0.5f;
                    }
            }
        }
        return out;
    }

    /** One coarse route (cells, goal end first), or null. */
    private int[] route(int fromX, int fromZ, Collection<Long> goals, float[] penalty) {
        int cells = n * n;
        boolean[] goal = new boolean[cells];
        int[] dist = new int[cells];
        Arrays.fill(dist, Integer.MAX_VALUE);
        int[] q = new int[cells];
        int head = 0, tail = 0;
        for (long g : goals) {
            int x = ChunkPos.getX(g), z = ChunkPos.getZ(g);
            if (Math.abs(x - sx) > radius || Math.abs(z - sz) > radius) continue;
            int c = cell(x, z);
            if (goal[c]) continue;
            goal[c] = true;
            dist[c] = 0;
            q[tail++] = c;
        }
        if (tail == 0) return null;
        while (head < tail) {
            int c = q[head++], i = c / n, j = c % n;
            int[] nb = {i > 0 ? c - n : -1, i < n - 1 ? c + n : -1, j > 0 ? c - 1 : -1, j < n - 1 ? c + 1 : -1};
            for (int m : nb) if (m >= 0 && dist[m] == Integer.MAX_VALUE) {
                dist[m] = dist[c] + 1;
                q[tail++] = m;
            }
        }
        float[] cost = new float[cells];
        Arrays.fill(cost, Float.POSITIVE_INFINITY);
        int[] parent = new int[cells];
        Arrays.fill(parent, -1);
        int start = cell(fromX, fromZ);
        stats(start);
        cost[start] = 0;
        PriorityQueue<float[]> open = new PriorityQueue<>((a, b) -> a[0] != b[0] ? Float.compare(a[0], b[0]) : Float.compare(a[1], b[1]));
        open.add(new float[]{dist[start] * CELL * 10f, start});
        int end = -1;
        while (!open.isEmpty()) {
            float[] e = open.poll();
            int c = (int) e[1];
            if (e[0] > cost[c] + dist[c] * CELL * 10f + 0.5f) continue;
            if (goal[c] && c != start) {
                end = c;
                break;
            }
            int i = c / n, j = c % n;
            int[] nb = {i > 0 ? c - n : -1, i < n - 1 ? c + n : -1, j > 0 ? c - 1 : -1, j < n - 1 ? c + 1 : -1};
            for (int m : nb) {
                if (m < 0) continue;
                float t = cost[c] + step(c, m, penalty);
                if (t >= cost[m]) continue;
                cost[m] = t;
                parent[m] = c;
                open.add(new float[]{t + dist[m] * CELL * 10f, m});
            }
        }
        if (end < 0) return null;
        it.unimi.dsi.fastutil.ints.IntArrayList path = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (int c = end; c >= 0; c = parent[c]) path.add(c);
        return path.toIntArray();
    }
}
