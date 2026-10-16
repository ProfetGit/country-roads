package io.github.profetgit.countryroads.gen;

import io.github.profetgit.countryroads.plan.ChunkMask;
import io.github.profetgit.countryroads.plan.EdgePlan;
import io.github.profetgit.countryroads.plan.RoadNetwork;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import io.github.profetgit.countryroads.paint.PaintRules;
import io.github.profetgit.countryroads.paint.RoadPainter;
import io.github.profetgit.countryroads.world.RoadColumns;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Per-thread state while a chunk runs its FEATURES step: the road masks of the chunk and its neighbours, whether a
 * feature is being placed (depth) and whether our own painter is writing (bypass).
 */
public final class GenContext {
    private static final ThreadLocal<GenContext> CURRENT = new ThreadLocal<>();
    /** Trees and huge mushrooms taken back because the road would have cut them (dev counter). */
    public static final java.util.concurrent.atomic.AtomicLong UNDONE = new java.util.concurrent.atomic.AtomicLong();

    private final RoadNetwork network;
    private final ServerLevel level;
    private final Long2ObjectOpenHashMap<ChunkMask> masks = new Long2ObjectOpenHashMap<>();
    int featureDepth;
    private boolean bypass;
    private int track, tn, undone;
    private boolean cut;
    private String cutAt;
    private long[] tpos = new long[256];
    private BlockState[] tprev = new BlockState[256];
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap grounds = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

    private GenContext(RoadNetwork network, ServerLevel level) {
        this.network = network;
        this.level = level;
    }

    /** The level whose chunk is generating on this thread (null outside a painting hook). */
    public static ServerLevel level() {
        GenContext c = CURRENT.get();
        return c == null ? null : c.level;
    }

    public static void begin(ServerLevel level, ChunkPos center) {
        RoadNetwork n = RoadNetwork.get(level);
        if (n == null) {
            CURRENT.remove();
            return;
        }
        GenContext c = new GenContext(n, level);
        boolean any = false;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                ChunkMask m = n.mask(center.x() + dx, center.z() + dz);
                c.masks.put(ChunkPos.pack(center.x() + dx, center.z() + dz), m);
                any |= m.any();
            }
        if (any) CURRENT.set(c);
        else CURRENT.remove();
    }

    public static void end() {
        CURRENT.remove();
    }

    public static ChunkMask mask(ChunkPos pos) {
        GenContext c = CURRENT.get();
        return c == null ? null : c.masks.get(pos.pack());
    }

    public static void enterFeature() {
        GenContext c = CURRENT.get();
        if (c != null) c.featureDepth++;
    }

    public static void exitFeature() {
        GenContext c = CURRENT.get();
        if (c != null && c.featureDepth > 0) c.featureDepth--;
    }

    public static void bypass(boolean on) {
        GenContext c = CURRENT.get();
        if (c != null) c.bypass = on;
    }

    private ChunkMask maskAt(int x, int z) {
        return masks.get(ChunkPos.pack(x >> 4, z >> 4));
    }

    /**
     * A feature write the painter would undo is dropped: on road columns everything from the old ground (or the road
     * surface) up to the road's head room or the old ground + 2, whichever is higher; a canopy may arch over the road
     * above that. While a tree or huge mushroom is being placed (tracking), a dropped log, stem or cap means the
     * painter would cut it: the whole feature is undone afterwards. Its logs may not reach low into the bank reach of a
     * chunk that is already painted either (the ground there may have been cut away from under them): up to BANK_LOG
     * over the shaped ground. Branches higher up (a big oak's crown over the cut) are held by the trunk and stay.
     */
    static final String DEBUG = System.getProperty("countryroads.debugColumn", "");
    private static final java.util.Set<String> DEBUG_COLS = java.util.Set.of(DEBUG.isEmpty() ? new String[0] : DEBUG.split(";"));

    /** Dev: this column is traced (-Dcountryroads.debugColumn=x,z or several joined by ';'). */
    public static boolean debug(int x, int z) {
        return !DEBUG_COLS.isEmpty() && DEBUG_COLS.contains(x + "," + z);
    }

    /** Dev: one CR-TRACE line with the calls that led here (short stack). */
    public static void trace(String what) {
        StringBuilder sb = new StringBuilder("CR-TRACE ").append(what).append(" @");
        StackTraceElement[] st = new Throwable().getStackTrace();
        for (int k = 1, n = 0; k < st.length && n < 9; k++) {
            String cl = st[k].getClassName();
            if (cl.contains("GenContext") || cl.contains("Canvas")) continue;
            sb.append(' ').append(cl.substring(cl.lastIndexOf('.') + 1)).append('.').append(st[k].getMethodName()).append(':').append(st[k].getLineNumber());
            n++;
        }
        io.github.profetgit.countryroads.CountryRoads.LOG.info(sb.toString());
    }
    /** Trees keep this far (blocks, square) from signposts and lamp posts: a crown low over a post would be cut by it. */
    static final int POST_CLEAR = 6;
    /** A log this far (or less) over a painted chunk's shaped ground in its bank reach undoes the tree. */
    static final int BANK_LOG = 2;
    /** Trees keep this far from a roadside prop's anchor (the widest prop reaches 5 blocks along the road, and a crown reaches further). */
    static final int PROP_CLEAR = 10;

    public static boolean guardWrite(WorldGenRegion region, BlockPos pos, BlockState state) {
        GenContext c = CURRENT.get();
        if (!DEBUG_COLS.isEmpty() && debug(pos.getX(), pos.getZ()) && (c == null || !c.bypass))
            trace("region " + pos.toShortString() + " " + region.getBlockState(pos) + " -> " + state + (c == null ? " noctx" : " depth=" + c.featureDepth + " track=" + c.track));
        if (c == null || c.bypass || c.featureDepth == 0) return false;
        boolean drop = false;
        int x = pos.getX(), z = pos.getZ();
        ChunkMask m = c.maskAt(x, z);
        if (m != null && m.any()) {
            int i = ChunkMask.idx(x & 15, z & 15);
            float in = m.in[i];
            if (in >= -0.6f) {
                int top = (int) Math.floor(m.surface(i)), g = c.ground(region, x, z, top);
                drop = pos.getY() >= Math.min(top, g + 1) && pos.getY() <= Math.max(top + 3, g + 2);
            }
            if (!drop && c.track > 0 && in >= -RoadPainter.CUT_REACH && woody(state) && painted(region, x >> 4, z >> 4)
                && pos.getY() <= c.ground(region, x, z, (int) Math.floor(m.surface(i))) + BANK_LOG) {
                drop = true;
                if (!c.cut) c.cutAt = "bank ";
            } else if (drop && !c.cut) c.cutAt = "clear ";
        }
        if (debug(x, z)) io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-TRACE write " + pos.toShortString() + " " + state + " drop=" + drop + " track=" + c.track);
        if (c.track > 0) {
            if (drop && woody(state) && !c.cut) c.cutAt = (c.cutAt == null ? "" : c.cutAt) + pos.toShortString() + " " + state.getBlock().getDescriptionId().replace("block.minecraft.", "") + " in=" + (m == null ? "?" : m.in[ChunkMask.idx(x & 15, z & 15)]);
            if (drop) c.cut |= woody(state);
            else c.record(pos, region.getBlockState(pos));
        }
        return drop;
    }

    /** Logs, roots and huge mushroom blocks: the parts of a tree that hold the rest up. */
    public static boolean woody(BlockState s) {
        return s.is(BlockTags.LOGS) || s.is(Blocks.MUSHROOM_STEM) || s.is(Blocks.BROWN_MUSHROOM_BLOCK) || s.is(Blocks.RED_MUSHROOM_BLOCK)
            || s.is(Blocks.MANGROVE_ROOTS) || s.is(Blocks.MUDDY_MANGROVE_ROOTS);
    }

    private static boolean painted(WorldGenRegion region, int cx, int cz) {
        if (!region.hasChunk(cx, cz)) return false;
        return ((AttachmentTarget) region.getChunk(cx, cz)).getAttached(RoadColumns.TYPE) != null;
    }

    /** The old ground under a road column (terrain as the painter will see it), cached per column. */
    private int ground(WorldGenRegion region, int x, int z, int top) {
        long key = ChunkPos.pack(x, z);
        int g = grounds.getOrDefault(key, Integer.MIN_VALUE);
        if (g != Integer.MIN_VALUE) return g;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int hi = Math.min(region.getMaxY() - 1, top + 24), lo = Math.max(region.getMinY(), top - 48);
        g = lo;
        for (int y = hi; y >= lo; y--)
            if (PaintRules.terrain(region.getBlockState(p.set(x, y, z)))) {
                g = y;
                break;
            }
        grounds.put(key, g);
        return g;
    }

    private void record(BlockPos pos, BlockState prev) {
        if (tn == tpos.length) {
            tpos = java.util.Arrays.copyOf(tpos, tn * 2);
            tprev = java.util.Arrays.copyOf(tprev, tn * 2);
        }
        tpos[tn] = pos.asLong();
        tprev[tn++] = prev;
    }

    /** Start recording a tree's writes; true when this call owns the recording (not nested in another tree). */
    public static boolean beginTree() {
        GenContext c = CURRENT.get();
        if (c == null) return false;
        if (c.track++ > 0) return false;
        c.cut = false;
        c.cutAt = null;
        c.tn = 0;
        return true;
    }

    /** Stop recording. When a part of the tree was dropped, every block it wrote is put back; returns true then. */
    public static boolean endTree(WorldGenLevel level, boolean owner) {
        GenContext c = CURRENT.get();
        if (c == null) return false;
        c.track--;
        if (!owner) return false;
        boolean undo = c.cut;
        if (undo) {
            boolean was = c.bypass;
            c.bypass = true;
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int k = c.tn - 1; k >= 0; k--) level.setBlock(p.set(c.tpos[k]), c.tprev[k], Block.UPDATE_CLIENTS);
            c.bypass = was;
            c.undone++;
            UNDONE.incrementAndGet();
            io.github.profetgit.countryroads.CountryRoads.LOG.info("CR-ITEM undone first=" + (c.tn > 0 ? BlockPos.of(c.tpos[0]).toShortString() : "-") + " n=" + c.tn + " cut=" + c.cutAt);
        }
        c.tn = 0;
        c.cut = false;
        c.cutAt = null;
        java.util.Arrays.fill(c.tprev, null);
        return undo;
    }

    /** Big features (trees, boulders, lakes, mushrooms) whose origin is this close to the road are not placed. */
    public static boolean blocksBigFeature(BlockPos origin, int radius) {
        return blocksBigFeature(origin, radius, false);
    }

    /** far: keep the full reach even where the road lies on the ground (huge mushrooms: their caps spread 3 blocks). */
    public static boolean blocksBigFeature(BlockPos origin, int radius, boolean far) {
        GenContext c = CURRENT.get();
        if (c == null || c.bypass) return false;
        // wheat fields: no trees, mushrooms or boulders in the blob (and 3 blocks round it)
        ChunkMask at = c.maskAt(origin.getX(), origin.getZ());
        if (at != null)
            for (io.github.profetgit.countryroads.plan.Field f : at.fields)
                if (f.covers(origin.getX(), origin.getZ(), 3 + Math.max(0, radius))) return true;
        // signposts and lamp posts need their column and arm free of trees
        if (radius == 0)
            for (ChunkMask m : c.masks.values())
                for (EdgePlan.Decor d : m.decor) {
                    int clear = d.type() == EdgePlan.PROP ? PROP_CLEAR : POST_CLEAR;
                    if (d.type() != EdgePlan.ORCHARD && Math.abs(d.x() - origin.getX()) <= clear && Math.abs(d.z() - origin.getZ()) <= clear) return true;
                }
        for (int dx = -radius; dx <= radius; dx += Math.max(1, radius)) {
            for (int dz = -radius; dz <= radius; dz += Math.max(1, radius)) {
                int x = origin.getX() + dx, z = origin.getZ() + dz;
                ChunkMask m = c.maskAt(x, z);
                if (m == null || !m.any()) continue;
                int i = ChunkMask.idx(x & 15, z & 15);
                // trees may stand at the shoulder where the road lies on the ground; where it cuts or fills, the
                // ground there will be reshaped, so they keep their distance
                float clearance = !far && radius == 0 && m.kind[i] == EdgePlan.GROUND && m.earth[i] <= 1 ? 1.5f : EdgePlan.REACH;
                if (m.in[i] >= -clearance) return true;
            }
        }
        return false;
    }
}
