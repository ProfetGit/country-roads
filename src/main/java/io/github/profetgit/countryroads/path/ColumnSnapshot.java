package io.github.profetgit.countryroads.path;

import io.github.profetgit.countryroads.retro.Retrofit;
import io.github.profetgit.countryroads.world.RoadColumns;
import it.unimi.dsi.fastutil.objects.Reference2ByteOpenHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The columns of the chunks a survey needs, captured once on the server thread into small immutable arrays, so the
 * search itself runs on a worker without touching the level. One column holds what PathSearch used to read live: the
 * usable ground, leaves just above it, the trunk standing in it, and the road bits.
 */
final class ColumnSnapshot {
    /** Leaves are recorded for this many blocks from the walking level up (the plan walks up to one block off it). */
    static final int LEAF_SPAN = 5;
    /** How far below a log-topped column's top the logs are recorded (through leaves and air: big oaks branch). */
    static final int LOG_SPAN = 64;

    /**
     * One chunk, columns indexed lz * 16 + lx. ground: as Terrain.ground. leaves: bit i = leaves at level(ground) + i.
     * logs: bit i = a log at logTop - i, for a column topped by a log or a built block (a tree, a house's posts; 0
     * elsewhere). road: RoadColumns bits.
     */
    record Chunk(int[] ground, byte[] leaves, short[] logTop, long[] logs, long[] road, long[] built) {
        static final Chunk MISSING;

        static {
            int[] g = new int[256];
            Arrays.fill(g, Terrain.CLOSED);
            MISSING = new Chunk(g, new byte[256], new short[256], new long[256], null, new long[4]);
        }

        boolean log(int i, int y) {
            int k = logTop[i] - y;
            return logs[i] != 0 && k >= 0 && k < LOG_SPAN && (logs[i] & (1L << k)) != 0;
        }

        boolean road(int i) {
            return road != null && (road[i >> 6] & (1L << (i & 63))) != 0;
        }

        /** The column is part of something a player (or a village) built (see build), and it is not a road. */
        boolean built(int i) {
            return (built[i >> 6] & (1L << (i & 63))) != 0;
        }

        boolean open(int i) {
            return road != null && road.length >= 8 && (road[4 + (i >> 6)] & (1L << (i & 63))) != 0;
        }
    }

    final Map<Long, Chunk> chunks = new ConcurrentHashMap<>();
    /** The captured chunks themselves: the worker reads the rock of a possible tunnel from them (see RouteSolver.safe). */
    final Map<Long, LevelChunk> live = new ConcurrentHashMap<>();

    /** Captures a loaded chunk into this snapshot. */
    void put(long key, LevelChunk c, int minY) {
        chunks.put(key, capture(c, minY));
        live.put(key, c);
    }

    private static final int LOG = 1, LEAF = 2, WATER = 4, FLUID = 8, CRAFTED = 16, STAKE = 32, KNOWN = 64, OPEN = 128;
    /** rock() answers per state (a holder class: a hot swap leaves new static fields of a loaded class null). */
    private static final class Rocks {
        static final it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap<BlockState> MAP = new it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap<>();
    }
    /** What a block state is, worked out once per state (tag lookups are the dear part of a capture). Server thread only. */
    private static final Reference2ByteOpenHashMap<BlockState> KINDS = new Reference2ByteOpenHashMap<>();

    /** Tags can change on a data pack reload. */
    static void forgetKinds() {
        KINDS.clear();
        Rocks.MAP.clear();
    }

    private static int kind(BlockState s) {
        int k = KINDS.getByte(s) & 0xFF;
        if (k != 0) return k;
        k = KNOWN;
        if (s.is(BlockTags.LOGS)) k |= LOG;
        if (s.is(BlockTags.LEAVES)) k |= LEAF;
        if (s.is(Blocks.WATER)) k |= WATER;
        if (!s.getFluidState().isEmpty()) k |= FLUID;
        if (Retrofit.isCrafted(s)) k |= CRAFTED;
        if (s.is(Stake.BLOCK)) k |= STAKE;
        if (!Heightmap.Types.MOTION_BLOCKING.isOpaque().test(s)) k |= OPEN;
        KINDS.put(s, (byte) k);
        return k;
    }

    /** Natural rock and soil: what cave crusts, cliff lips and overhangs are made of (terracotta is not: a house wall). */
    private static boolean rock(BlockState s) {
        int r = Rocks.MAP.getInt(s);
        if (r != 0) return r > 0;
        boolean v = s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.DIRT) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.PODZOL) || s.is(Blocks.MYCELIUM)
            || s.is(Blocks.MUD) || s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.MUDDY_MANGROVE_ROOTS) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY)
            || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE) || s.is(Blocks.CALCITE) || s.is(Blocks.DRIPSTONE_BLOCK) || s.is(Blocks.SNOW_BLOCK)
            || s.is(BlockTags.ICE) || s.is(Blocks.MUSHROOM_STEM) || s.is(Blocks.BROWN_MUSHROOM_BLOCK) || s.is(Blocks.RED_MUSHROOM_BLOCK)
            || s.is(Blocks.SMOOTH_BASALT) || s.is(Blocks.AMETHYST_BLOCK) || s.is(BlockTags.IRON_ORES) || s.is(BlockTags.COPPER_ORES) || s.is(BlockTags.GOLD_ORES) || s.is(Blocks.COAL_ORE);
        Rocks.MAP.put(s, v ? 1 : -1);
        return v;
    }

    /** How far under a column's top a build is looked for. */
    static final int BUILD_SPAN = 8;
    /** A room's roof is at most this thick, and the room at most ROOM_MAX tall: a storey, not a cave. */
    static final int ROOF_MAX = 2, ROOM_MAX = 6;

    /**
     * The column is part of a build, told by its shape rather than its blocks: under its top there is a room (two or
     * more blocks a player could stand in, over a floor), or anything only a builder places (a door, a window pane, a
     * torch...) sits anywhere in it. A room is a storey: a roof at most ROOF_MAX thick that is not natural rock or soil,
     * over two to ROOM_MAX open blocks and a floor. A terracotta or concrete-less stone-brick house has rooms like any
     * other (user 2026-11-17: a path graded a white terracotta house into a stepped mound, its roof read as a hill);
     * cave crusts and cliff lips are rock or soil, so mountain tunnels and overhang routes stay as they were. Server
     * thread (the kind cache).
     */
    static boolean build(BlockGetter g, BlockPos.MutableBlockPos p, int x, int top, int z, int minY) {
        return why(g, p, x, top, z, minY, null, null) != null;
    }

    /**
     * Why the column is part of a build (null: it is not). With a chunk and its per-section memo, a column topped by
     * rock or soil (no room possible) whose sections hold no builder block anywhere in their palettes is answered
     * without reading it: most natural columns cost one read.
     */
    static String why(BlockGetter g, BlockPos.MutableBlockPos p, int x, int top, int z, int minY, LevelChunk c, byte[] may) {
        if (c != null && rock(g.getBlockState(p.set(x, top, z))) && !mayCraft(c, may, top) && !mayCraft(c, may, top - BUILD_SPAN)) return null;
        int open = 0, roof = 0;
        boolean natural = false;
        BlockState roofTop = null;
        for (int q = 0; q <= BUILD_SPAN && top - q > minY; q++) {
            BlockState s = g.getBlockState(p.set(x, top - q, z));
            int k = kind(s);
            if ((k & CRAFTED) != 0) return "builder " + s.getBlock().getDescriptionId().replace("block.minecraft.", "") + " at -" + q;
            if ((k & OPEN) != 0) {
                if (roof == 0) continue;
                open++;
            } else if (open == 0) {
                if (roofTop == null) roofTop = s;
                if (++roof > ROOF_MAX || rock(s)) natural = true;
            } else {
                if (!natural && open >= 2 && open <= ROOM_MAX)
                    return "room under " + roofTop.getBlock().getDescriptionId().replace("block.minecraft.", "") + " roof " + roof + " high " + open;
                // below the first room: only builder blocks count from here
                natural = true;
                open = 0;
            }
        }
        return null;
    }

    /** Whether the chunk section holding y may hold a builder block (its palette, memoised: 0 unknown, 1 yes, 2 no). */
    private static boolean mayCraft(LevelChunk c, byte[] may, int y) {
        int i = c.getSectionIndex(y);
        if (i < 0 || i >= may.length) return false;
        if (may[i] == 0) may[i] = c.getSection(i).maybeHas(s -> (kind(s) & CRAFTED) != 0) ? (byte) 1 : (byte) 2;
        return may[i] == 1;
    }

    Chunk get(int cx, int cz) {
        return chunks.get(ChunkPos.pack(cx, cz));
    }

    /** Reads one loaded chunk (server thread). About 1-2k block reads; nothing outside the chunk. */
    static Chunk capture(LevelChunk c, int minY) {
        RoadColumns rc = RoadColumns.get(c);
        long[] road = rc == null || !rc.any() ? null : rc.bits().clone();
        int[] ground = new int[256];
        byte[] leaves = new byte[256];
        short[] logTop = new short[256];
        long[] logs = new long[256];
        long[] built = new long[4];
        int bx = c.getPos().getMinBlockX(), bz = c.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        byte[] may = new byte[c.getSectionsCount()];
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                int i = (lz << 4) | lx, x = bx + lx, z = bz + lz;
                int top = c.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz);
                int k = kind(c.getBlockState(p.set(x, top, z)));
                if ((k & STAKE) != 0) {
                    // a waypoint (or another door) stands here: the ground is the block under it
                    top--;
                    k = kind(c.getBlockState(p.set(x, top, z)));
                }
                if ((k & (LOG | CRAFTED)) != 0) {
                    // a tree: every log down to the ground, through the leaves and air of its crown; a building: its posts
                    long m = (k & LOG) != 0 ? 1 : 0;
                    for (int q = 1; q < LOG_SPAN && top - q > minY; q++) {
                        BlockState b = c.getBlockState(p.set(x, top - q, z));
                        int bk = kind(b);
                        if ((bk & LOG) != 0) m |= 1L << q;
                        else if ((bk & (LEAF | CRAFTED)) == 0 && !b.isAir()) break;
                    }
                    logTop[i] = (short) top;
                    logs[i] = m;
                }
                int g;
                if ((k & WATER) != 0) {
                    int below = top;
                    while (below > minY && (kind(c.getBlockState(p.set(x, below, z))) & WATER) != 0 && top - below < 6) below--;
                    g = top - below >= 6 ? Terrain.DEEP + top : top + Terrain.WET;
                } else if ((k & (FLUID | LOG)) != 0) {
                    g = Terrain.CLOSED;
                    if ((k & CRAFTED) != 0) built[i >> 6] |= 1L << (i & 63);
                } else if (!(road != null && rc.road(lx, lz)) && ((k & CRAFTED) != 0 || why(c, p, x, top, z, minY, c, may) != null)) {
                    g = Terrain.CLOSED;
                    built[i >> 6] |= 1L << (i & 63);
                } else g = top;
                ground[i] = g;
                // leaves above the ground raise MOTION_BLOCKING over MOTION_BLOCKING_NO_LEAVES: without that, none to look for
                if (Terrain.usable(g) && c.getHeight(Heightmap.Types.MOTION_BLOCKING, lx, lz) > top) {
                    int base = Terrain.level(g), m = 0;
                    for (int q = 0; q < LEAF_SPAN; q++) if ((kind(c.getBlockState(p.set(x, base + q, z))) & LEAF) != 0) m |= 1 << q;
                    leaves[i] = (byte) m;
                }
            }
        return new Chunk(ground, leaves, logTop, logs, road, built);
    }

    /**
     * The snapshot as one survey sees it: a window around the stake with the per-column lookups memoised, and the
     * door and waypoint columns overridden (their stake stands on whatever the player built, which is not ground).
     */
    static final class View implements Terrain {
        final ColumnSnapshot snap;
        final int x0, z0, w;
        int minY = Integer.MIN_VALUE / 2;
        private final int[] ground;
        private final long[] overrides;
        private final int[] overrideY;
        /** Captured chunks of the window by chunk position (filled as they are found; a captured chunk never changes here). */
        private final Chunk[] chunkCache;
        private final int cx0, cz0, cw;

        View(ColumnSnapshot snap, int cx, int cz, int radius, List<long[]> overrides) {
            this.snap = snap;
            this.x0 = cx - radius - 1;
            this.z0 = cz - radius - 1;
            this.w = 2 * radius + 3;
            this.ground = new int[w * w];
            Arrays.fill(ground, UNKNOWN);
            this.cx0 = x0 >> 4;
            this.cz0 = z0 >> 4;
            this.cw = ((x0 + w - 1) >> 4) - cx0 + 1;
            this.chunkCache = new Chunk[cw * cw];
            this.overrides = new long[overrides.size()];
            this.overrideY = new int[overrides.size()];
            for (int i = 0; i < overrides.size(); i++) {
                this.overrides[i] = overrides.get(i)[0];
                this.overrideY[i] = (int) overrides.get(i)[1];
            }
        }

        boolean inside(int x, int z) {
            return x >= x0 && z >= z0 && x < x0 + w && z < z0 + w;
        }

        Chunk chunk(int x, int z) {
            int cx = (x >> 4) - cx0, cz = (z >> 4) - cz0;
            if (cx < 0 || cz < 0 || cx >= cw || cz >= cw) return snap.get(x >> 4, z >> 4);
            int i = cx * cw + cz;
            Chunk c = chunkCache[i];
            if (c == null) {
                c = snap.get(x >> 4, z >> 4);
                if (c != null) chunkCache[i] = c;
            }
            return c;
        }

        boolean known(int x, int z) {
            return chunk(x, z) != null;
        }

        /** Forget memoised columns of a chunk (it was captured again). */
        void forget(int cx, int cz) {
            int ci = cx - cx0, cj = cz - cz0;
            if (ci >= 0 && cj >= 0 && ci < cw && cj < cw) chunkCache[ci * cw + cj] = null;
            for (int x = cx << 4; x < (cx << 4) + 16; x++)
                for (int z = cz << 4; z < (cz << 4) + 16; z++)
                    if (inside(x, z)) ground[(x - x0) * w + (z - z0)] = UNKNOWN;
        }

        @Override
        public int ground(int x, int z) {
            boolean in = inside(x, z);
            int idx = in ? (x - x0) * w + (z - z0) : -1;
            if (in && ground[idx] != UNKNOWN) return ground[idx];
            long k = ChunkPos.pack(x, z);
            for (int i = 0; i < overrides.length; i++) if (overrides[i] == k) {
                if (in) ground[idx] = overrideY[i];
                return overrideY[i];
            }
            Chunk c = chunk(x, z);
            if (c == null) return UNKNOWN;
            int g = c.ground[((z & 15) << 4) | (x & 15)];
            if (in) ground[idx] = g;
            return g;
        }

        @Override
        public int treeCost(int x, int z, int walkY) {
            int v = 0;
            Chunk c = chunk(x, z);
            if (c != null) {
                int i = ((z & 15) << 4) | (x & 15);
                int g = c.ground[i];
                if (Terrain.usable(g)) {
                    int base = Terrain.level(g);
                    for (int dy = 1; dy <= HousePaths.LEAF_ROOM; dy++) {
                        int k = walkY + dy - base;
                        if (k >= 0 && k < LEAF_SPAN && (c.leaves[i] & (1 << k)) != 0) v += 14;
                    }
                }
            }
            for (int d = 0; d < 4; d++) {
                int nx = x + HousePaths.DX[d], nz = z + HousePaths.DZ[d];
                Chunk n = chunk(nx, nz);
                if (n == null) continue;
                int i = ((nz & 15) << 4) | (nx & 15);
                if (n.log(i, walkY + 1)) v += 8;
            }
            return v;
        }

        @Override
        public boolean road(int x, int z) {
            Chunk c = chunk(x, z);
            return c != null && c.road(((z & 15) << 4) | (x & 15));
        }

        @Override
        public boolean built(int x, int z) {
            Chunk c = chunk(x, z);
            return c != null && c.built(((z & 15) << 4) | (x & 15));
        }

        @Override
        public boolean openRoad(int x, int z) {
            Chunk c = chunk(x, z);
            return c != null && c.open(((z & 15) << 4) | (x & 15));
        }
    }
}
