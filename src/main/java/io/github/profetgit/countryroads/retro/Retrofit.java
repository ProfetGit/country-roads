package io.github.profetgit.countryroads.retro;

import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.paint.Canvas;
import io.github.profetgit.countryroads.paint.RoadPainter;
import io.github.profetgit.countryroads.plan.ChunkMask;
import io.github.profetgit.countryroads.plan.RoadNetwork;
import io.github.profetgit.countryroads.world.RoadColumns;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Old worlds: chunks generated before Country Roads was installed get their road the first time they load. Plans
 * are the same seed-fixed plans as for new chunks, so a road continues seamlessly across the old/new border. Chunks
 * players have lived in (or next to), and chunks with crafted blocks near the road, are never touched: the road
 * stops short of them.
 */
public final class Retrofit {
    /** About one hour of a player nearby, in ticks. */
    public static final long LIVED_IN = 72_000;
    private static final long BUDGET_NANOS = 3_000_000;

    private record Job(ServerLevel level, ChunkPos pos, ChunkMask mask, java.util.List<io.github.profetgit.countryroads.world.StreetBands> streets) {}

    private static final ConcurrentLinkedQueue<Job> READY = new ConcurrentLinkedQueue<>();
    /** Its own threads: a job waits on road plans, so it must never occupy a planner thread (deadlock). */
    private static final java.util.concurrent.ExecutorService WAITERS = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "CountryRoads-retrofit");
        t.setDaemon(true);
        return t;
    });
    private static final Set<Long> PENDING = ConcurrentHashMap.newKeySet();
    public static final java.util.Map<String, Integer> craftedSeen = new java.util.concurrent.ConcurrentHashMap<>();
    public static long queued, painted, skippedLivedIn, skippedCrafted, plain, streetsPainted;
    /** Tests only: hold finished jobs until resumed (-Dcountryroads.retrofit.paused=true). */
    public static volatile boolean paused = Boolean.getBoolean("countryroads.retrofit.paused");

    private Retrofit() {}

    public static void onLoad(ServerLevel level, LevelChunk chunk, boolean generated) {
        if (generated || RoadColumns.handled(chunk)) return;
        RoadNetwork n = RoadNetwork.get(level);
        if (n == null) return;
        ChunkPos pos = chunk.getPos();
        if (!PENDING.add(pos.pack())) return;
        queued++;
        CompletableFuture.supplyAsync(() -> new Job(level, pos, n.mask(pos.x(), pos.z()), n.streets(pos.x(), pos.z())), WAITERS)
            .whenComplete((m, err) -> {
                if (err != null) {
                    PENDING.remove(pos.pack());
                    CountryRoads.LOG.error("Road retrofit planning failed for {}", pos, err);
                } else READY.add(m);
            });
    }

    public static boolean busy() {
        return !paused && !PENDING.isEmpty();
    }

    public static void tick() {
        if (paused) return;
        long until = System.nanoTime() + BUDGET_NANOS;
        Job j;
        while (System.nanoTime() < until && (j = READY.poll()) != null) {
            PENDING.remove(j.pos.pack());
            try {
                apply(j);
            } catch (Throwable t) {
                CountryRoads.LOG.error("Road retrofit failed for {}", j.pos, t);
            }
        }
    }

    private static void apply(Job j) {
        LevelChunk chunk = j.level.getChunkSource().getChunkNow(j.pos.x(), j.pos.z());
        if (chunk == null || RoadColumns.handled(chunk)) return;
        RoadColumns cols = RoadColumns.empty();
        boolean lived = livedIn(j.level, j.pos);
        if (!j.mask.any()) plain++;
        else {
            if (lived) skippedLivedIn++;
            else if (crafted(j.level, j.mask)) skippedCrafted++;
            else {
                RoadPainter.paint(Canvas.live(j.level, chunk), j.mask, cols);
                painted++;
            }
        }
        if (!lived && !j.streets.isEmpty()) streetsPainted += io.github.profetgit.countryroads.paint.StreetPainter.paint(Canvas.live(j.level, chunk), j.streets, j.pos.x(), j.pos.z(), cols);
        RoadColumns.store(chunk, cols);
        chunk.markUnsaved();
    }

    /** This chunk or a loaded neighbour has had players around for a while. */
    static boolean livedIn(ServerLevel level, ChunkPos pos) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                LevelChunk c = level.getChunkSource().getChunkNow(pos.x() + dx, pos.z() + dz);
                if (c != null && c.getInhabitedTime() >= LIVED_IN) return true;
            }
        return false;
    }

    /** Crafted blocks near the road mean someone built here: leave the chunk alone. */
    static boolean crafted(ServerLevel level, ChunkMask m) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 256; i++) {
            if (m.in[i] < -2.5f) continue;
            int x = (m.cx << 4) + (i & 15), z = (m.cz << 4) + (i >> 4);
            int top = (int) Math.floor(m.surface(i));
            for (int y = top - 2; y <= top + 6; y++)
                if (isCrafted(level.getBlockState(p.set(x, y, z)))) {
                    craftedSeen.merge(level.getBlockState(p).getBlock().getDescriptionId().replace("block.minecraft.", ""), 1, Integer::sum);
                    return true;
                }
        }
        return false;
    }

    public static boolean isCrafted(BlockState s) {
        return s.is(BlockTags.PLANKS) || s.is(BlockTags.WOOL) || s.is(BlockTags.BEDS) || s.is(BlockTags.DOORS) || s.is(Blocks.CHEST)
            || s.is(Blocks.CRAFTING_TABLE) || s.is(Blocks.FURNACE) || s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH) || s.is(Blocks.GLASS)
            || s.is(BlockTags.FENCES) || s.is(Blocks.FARMLAND) || s.is(BlockTags.STAIRS) || s.is(BlockTags.SLABS) || s.is(Blocks.COBBLESTONE)
            || s.is(Blocks.STONE_BRICKS) || s.is(BlockTags.SIGNS) || s.is(Blocks.LANTERN)
            || s.is(BlockTags.WALLS) || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.TRAPDOORS) || s.is(BlockTags.IMPERMEABLE) || s.is(Blocks.BRICKS)
            || s.is(BlockTags.BANNERS) || s.is(BlockTags.CAMPFIRES) || s.is(Blocks.BOOKSHELF);
    }
}
