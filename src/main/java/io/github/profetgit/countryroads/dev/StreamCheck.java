package io.github.profetgit.countryroads.dev;

import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.plan.ChunkMask;
import io.github.profetgit.countryroads.plan.EdgePlan;
import io.github.profetgit.countryroads.plan.RoadNetwork;
import io.github.profetgit.countryroads.world.TerrainSampler;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Where roads meet water the planner did not know about (another mod's streams and lakes: water in a column whose
 * noise column is dry there). Per road column: stream water on or in the road (wet), the road block itself gone (cut),
 * stream water beside the road above its surface (spill), stream water under a ground road (undercut). Sites cluster
 * road columns with stream water in them or within 1; each logs a CR-STREAM-SITE line with the road kinds and a camera.
 * Then the water near every site is snapshotted, the chunks are force-loaded for {@code ticks} ticks and compared:
 * water that appears or drains away near a road is broken water (CR-STREAM-FLOW).
 */
final class StreamCheck {
    private StreamCheck() {}

    private static final int UP = 3, NEAR = 3;

    private static void report(CommandSourceStack src, String line) {
        CountryRoads.LOG.info(line);
        src.sendSuccess(() -> Component.literal(line), false);
    }

    private record Col(int x, int z, int top, byte kind, boolean conn) {}

    static int run(CommandSourceStack src, int radius, int ticks) {
        ServerLevel level = src.getLevel();
        RoadNetwork n = RoadNetwork.get(level);
        if (n == null) {
            report(src, "CR-STREAM FAIL no road network in this level");
            return 0;
        }
        TerrainSampler terrain = n.terrain;
        BlockPos at = BlockPos.containing(src.getPosition());
        int r = radius >> 4, x0 = (at.getX() >> 4) - r, z0 = (at.getZ() >> 4) - r, side = 2 * r + 1, total = side * side;
        int[] next = {0};
        List<Col> road = new ArrayList<>();
        Long2IntOpenHashMap roadTop = new Long2IntOpenHashMap();
        roadTop.defaultReturnValue(Integer.MIN_VALUE);
        int[] k = new int[10]; // 0 roadCols 1 wet 2 cut 3 spill 4 undercut 5 streamNear 6 fords 7 decks 8 buried 9 open
        List<int[]> hits = new ArrayList<>(); // x, z, top, kind, flags
        Long2ObjectOpenHashMap<int[]> snapshot = new Long2ObjectOpenHashMap<>();
        List<long[]> forced = new ArrayList<>();
        int[] phase = {0}, shownUnder = {0};
        long[] tickEnd = {0};
        DevCommands.queue(() -> {
            long until = System.nanoTime() + 40_000_000L;
            if (phase[0] == 0) {
                while (next[0] < total && System.nanoTime() < until) {
                    int i = next[0]++;
                    int cx = x0 + i % side, cz = z0 + i / side;
                    level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, true);
                    ChunkMask m = n.mask(cx, cz);
                    if (!m.any()) continue;
                    for (int c = 0; c < 256; c++) {
                        int lx = c & 15, lz = c >> 4, mi = ChunkMask.idx(lx, lz);
                        if (!(m.in[mi] >= 0)) continue;
                        int top = (int) Math.floor(m.surface(mi));
                        int x = (cx << 4) + lx, z = (cz << 4) + lz;
                        road.add(new Col(x, z, top, m.kind[mi], m.conn[mi]));
                        roadTop.put(BlockPos.asLong(x, 0, z), top);
                    }
                }
                if (next[0] < total) return false;
                next[0] = 0;
                phase[0] = 1;
                return false;
            }
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            if (phase[0] == 1) {
                while (next[0] < road.size() && System.nanoTime() < until) {
                    Col c = road.get(next[0]++);
                    k[0]++;
                    int flags = 0;
                    // the road column itself: water on the road (a ford's one layer at the road block over a bed is fine), the
                    // road block gone, water right under a ground road that is not a deck (a filled-over or undercut channel)
                    BlockState topState = level.getBlockState(p.set(c.x, c.top, c.z));
                    // planks/logs of a stream bridge, or the slab, trapdoor and campfire pieces of a walkway near a boardwalk
                    boolean decked = topState.is(net.minecraft.tags.BlockTags.PLANKS) || topState.is(net.minecraft.tags.BlockTags.LOGS)
                        || topState.is(net.minecraft.tags.BlockTags.WOODEN_SLABS) || topState.is(net.minecraft.tags.BlockTags.WOODEN_TRAPDOORS)
                        || topState.is(net.minecraft.world.level.block.Blocks.CAMPFIRE) || topState.is(net.minecraft.tags.BlockTags.WOODEN_STAIRS);
                    // a walkway's dry row cut down a block to its layer: the piece under the planned road block
                    BlockState under = level.getBlockState(p.set(c.x, c.top - 1, c.z));
                    boolean walkLow = topState.isAir() && (RoadChecks.walkwayPiece(under)
                        || io.github.profetgit.countryroads.paint.PaintRules.solid(under) && under.getFluidState().isEmpty()
                            && RoadChecks.walkwayNear(level, new BlockPos.MutableBlockPos(), c.x, c.top - 1, c.z));
                    boolean ford = water(level, p.set(c.x, c.top, c.z)) && !water(level, p.set(c.x, c.top + 1, c.z))
                        && io.github.profetgit.countryroads.paint.PaintRules.solid(level.getBlockState(p.set(c.x, c.top - 1, c.z)));
                    for (int y = c.top; y <= c.top + UP; y++)
                        if (water(level, p.set(c.x, y, c.z)) && stream(terrain, c.x, y, c.z)) flags |= y == c.top && ford ? 32 | 16 : 1 | 16;
                    // beside a crossing laid square on a diagonal road the road's own columns keep the stream open (RoadPainter.square)
                    boolean open = !ford && (topState.isAir() || !topState.getFluidState().isEmpty())
                        && RoadChecks.bridgeNear(level, new BlockPos.MutableBlockPos(), c.x, c.top, c.z);
                    if (open) k[9]++;
                    if (c.kind != EdgePlan.TUNNEL && !c.conn && !ford && !open && !walkLow && (topState.isAir() || !topState.getFluidState().isEmpty())) flags |= 2;
                    // water two down under a solid block that is not the road's (a pocket the ground already covered before
                    // the road came: traced at 77,-3 and 231,237 on the user seed) is buried, not undercut
                    if (c.kind == EdgePlan.GROUND || c.kind == EdgePlan.CAUSEWAY)
                        for (int y = c.top - 2; y < c.top; y++)
                            if (water(level, p.set(c.x, y, c.z)) && stream(terrain, c.x, y, c.z)) {
                                boolean buried = y == c.top - 2 && !decked && io.github.profetgit.countryroads.paint.PaintRules.solid(level.getBlockState(p.set(c.x, c.top - 1, c.z)))
                                    && level.getBlockState(p).getFluidState().isEmpty();
                                flags |= (decked || open ? 64 : buried ? 128 : 8) | 16;
                            }
                    // stream water beside the road: the neighbour column's topmost liquid
                    for (int dx = -1; dx <= 1; dx++)
                        for (int dz = -1; dz <= 1; dz++) {
                            if ((dx | dz) == 0 || roadTop.get(BlockPos.asLong(c.x + dx, 0, c.z + dz)) != Integer.MIN_VALUE) continue;
                            int wy = surfaceWater(level, p, c.x + dx, c.z + dz, c.top - 6, c.top + UP);
                            if (wy == Integer.MIN_VALUE || !stream(terrain, c.x + dx, wy, c.z + dz)) continue;
                            flags |= 16;
                            if (wy >= c.top + 1) flags |= 4;
                        }
                    if ((flags & 1) != 0) k[1]++;
                    if ((flags & 2) != 0) k[2]++;
                    if ((flags & 4) != 0) k[3]++;
                    if ((flags & 8) != 0) k[4]++;
                    if ((flags & 32) != 0) k[6]++;
                    if ((flags & 64) != 0) k[7]++;
                    if ((flags & 128) != 0) k[8]++;
                    if ((flags & 8) != 0 && shownUnder[0]++ < 20) report(src, "CR-STREAM-UNDERCUT at " + c.x + " " + c.top + " " + c.z + " top=" + topState.getBlock().getDescriptionId().replace("block.minecraft.", ""));
                    if ((flags & 16) != 0) {
                        k[5]++;
                        hits.add(new int[]{c.x, c.z, c.top, c.kind, flags});
                    }
                }
                if (next[0] < road.size()) return false;
                // sites: hits within 10 blocks of each other
                List<List<int[]>> sites = new ArrayList<>();
                for (int[] h : hits) {
                    List<int[]> home = null;
                    for (List<int[]> s : sites)
                        for (int[] o : s)
                            if (Math.abs(o[0] - h[0]) <= 10 && Math.abs(o[1] - h[1]) <= 10) {
                                home = s;
                                break;
                            }
                    if (home == null) sites.add(home = new ArrayList<>());
                    home.add(h);
                }
                int bad = 0, shown = 0;
                for (List<int[]> s : sites) {
                    int[] kinds = new int[5];
                    int wet = 0, cut = 0, spill = 0, under = 0, fords = 0, decks = 0;
                    long sx = 0, sz = 0;
                    int ty = Integer.MIN_VALUE;
                    for (int[] h : s) {
                        kinds[Math.min(4, h[3])]++;
                        if ((h[4] & 1) != 0) wet++;
                        if ((h[4] & 2) != 0) cut++;
                        if ((h[4] & 4) != 0) spill++;
                        if ((h[4] & 8) != 0) under++;
                        if ((h[4] & 32) != 0) fords++;
                        if ((h[4] & 64) != 0) decks++;
                        sx += h[0];
                        sz += h[1];
                        ty = Math.max(ty, h[2]);
                    }
                    int mx = (int) (sx / s.size()), mz = (int) (sz / s.size());
                    boolean b = wet + cut + spill + under > 0;
                    if (b) bad++;
                    if (shown++ < 80)
                        report(src, "CR-STREAM-SITE " + (b ? "BAD" : "ok") + " at " + mx + " " + ty + " " + mz + " cols=" + s.size()
                            + " ground=" + kinds[EdgePlan.GROUND] + " causeway=" + kinds[EdgePlan.CAUSEWAY] + " bridge=" + kinds[EdgePlan.BRIDGE]
                            + " boardwalk=" + kinds[EdgePlan.BOARDWALK] + " tunnel=" + kinds[EdgePlan.TUNNEL]
                            + " wet=" + wet + " cut=" + cut + " spill=" + spill + " undercut=" + under + " fords=" + fords + " decks=" + decks
                            + " cam=" + (mx + 6) + " " + (ty + 6) + " " + (mz + 6));
                    // water within NEAR of the site's road columns, for the flow test
                    for (int[] h : s)
                        for (int dx = -NEAR; dx <= NEAR; dx++)
                            for (int dz = -NEAR; dz <= NEAR; dz++)
                                for (int y = h[2] - 4; y <= h[2] + UP; y++) {
                                    long key = BlockPos.asLong(h[0] + dx, y, h[1] + dz);
                                    if (!snapshot.containsKey(key)) snapshot.put(key, new int[]{fluid(level, p.set(h[0] + dx, y, h[1] + dz))});
                                }
                }
                report(src, "CR-STREAM-SITES sites=" + sites.size() + " bad=" + bad);
                if (REF != null) reference(src, level, terrain, road, roadTop, p);
                report(src, "CR-STREAM-COLS roadCols=" + k[0] + " streamNear=" + k[5] + " wet=" + k[1] + " cut=" + k[2] + " spill=" + k[3] + " undercut=" + k[4] + " fords=" + k[6] + " decks=" + k[7] + " buried=" + k[8] + " open=" + k[9]);
                if (ticks <= 0 || snapshot.isEmpty()) {
                    report(src, "CR-STREAM " + (k[1] + k[2] + k[3] + k[4] == 0 ? "PASS" : "FAIL") + " flow=skipped");
                    return true;
                }
                it.unimi.dsi.fastutil.longs.LongOpenHashSet chunks = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
                for (long key : snapshot.keySet()) chunks.add(((long) (BlockPos.getX(key) >> 4) << 32) ^ ((BlockPos.getZ(key) >> 4) & 0xffffffffL));
                for (long ck : chunks) {
                    int cx = (int) (ck >> 32), cz = (int) ck;
                    if (level.setChunkForced(cx, cz, true)) forced.add(new long[]{cx, cz});
                }
                tickEnd[0] = level.getGameTime() + ticks;
                phase[0] = 2;
                return false;
            }
            if (level.getGameTime() < tickEnd[0]) return false;
            int appeared = 0, drained = 0, shown = 0;
            for (var e : snapshot.long2ObjectEntrySet()) {
                long key = e.getLongKey();
                int before = e.getValue()[0], after = fluid(level, p.set(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key)));
                if (before == after) continue;
                if (after > before) appeared++;
                else drained++;
                if (shown++ < 40)
                    report(src, "CR-STREAM-FLOW " + (after > before ? "appeared" : "drained") + " at " + BlockPos.getX(key) + " " + BlockPos.getY(key) + " " + BlockPos.getZ(key)
                        + " " + before + "->" + after);
            }
            for (long[] f : forced) level.setChunkForced((int) f[0], (int) f[1], false);
            report(src, "CR-STREAM " + (k[1] + k[2] + k[3] + k[4] + appeared + drained == 0 ? "PASS" : "FAIL")
                + " wet=" + k[1] + " cut=" + k[2] + " spill=" + k[3] + " undercut=" + k[4] + " appeared=" + appeared + " drained=" + drained
                + " watched=" + snapshot.size() + " ticks=" + ticks);
            return true;
        });
        return 1;
    }

    /** 0 = no water, 1..8 flowing amount, 9 = source. */
    private static int fluid(ServerLevel level, BlockPos p) {
        FluidState f = level.getFluidState(p);
        if (f.isEmpty() || !(f.is(Fluids.WATER) || f.is(Fluids.FLOWING_WATER))) return 0;
        return f.isSource() ? 9 : f.getAmount();
    }

    /**
     * -Dcountryroads.streamRef=FILE: with noPaint, the stream water within REACH of every planned road column is written
     * there (x z y of the column's topmost liquid); without, the painted world is compared with it: stream water the road
     * filled (on a road column) or drained (beside it), and water that is new or at another height.
     */
    static final String REF = System.getProperty("countryroads.streamRef");
    private static final int REACH = 4;

    private static boolean timberAt(ServerLevel level, BlockPos.MutableBlockPos p, int x, int y, int z) {
        BlockState s = level.getBlockState(p.set(x, y, z));
        return s.is(net.minecraft.tags.BlockTags.LOGS) || s.is(net.minecraft.tags.BlockTags.PLANKS) || s.is(net.minecraft.tags.BlockTags.WOODEN_STAIRS);
    }

    private static void reference(CommandSourceStack src, ServerLevel level, TerrainSampler terrain, List<Col> road, Long2IntOpenHashMap roadTop,
                                  BlockPos.MutableBlockPos p) {
        Long2IntOpenHashMap now = new Long2IntOpenHashMap();
        now.defaultReturnValue(Integer.MIN_VALUE);
        it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap kinds = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();
        for (Col c : road) kinds.put(BlockPos.asLong(c.x, 0, c.z), c.kind);
        Long2IntOpenHashMap near = new Long2IntOpenHashMap(); // column -> nearest road top
        for (Col c : road)
            for (int dx = -REACH; dx <= REACH; dx++)
                for (int dz = -REACH; dz <= REACH; dz++) {
                    long key = BlockPos.asLong(c.x + dx, 0, c.z + dz);
                    if (!near.containsKey(key)) near.put(key, c.top);
                }
        for (var e : near.long2IntEntrySet()) {
            int x = BlockPos.getX(e.getLongKey()), z = BlockPos.getZ(e.getLongKey()), top = e.getIntValue();
            int wy = surfaceWater(level, p, x, z, top - 10, top + 6);
            // under a painted road the stream is below the road block: look under it too
            // ...from the column's own road block (the nearest road's top can be a block off), and under a bridge's
            // stringer or fence beside the road (painted worlds only, so the reference keeps its meaning)
            int own = roadTop.get(e.getLongKey());
            boolean timber = false;
            if (!io.github.profetgit.countryroads.paint.RoadPainter.NO_PAINT)
                for (int y = top - 1; y <= top + 3 && !timber; y++) timber = timberAt(level, p, x, y, z);
            if (wy == Integer.MIN_VALUE && (own != Integer.MIN_VALUE || timber))
                // (from the road block itself: a long ford keeps the stream at the road's height under a walkway piece)
                for (int y = own != Integer.MIN_VALUE ? own : top + 2; y >= top - 10 && wy == Integer.MIN_VALUE; y--)
                    if (water(level, p.set(x, y, z))) wy = y;
            if (wy != Integer.MIN_VALUE && stream(terrain, x, wy, z)) now.put(e.getLongKey(), wy);
        }
        java.nio.file.Path f = java.nio.file.Path.of(REF);
        try {
            if (io.github.profetgit.countryroads.paint.RoadPainter.NO_PAINT) {
                StringBuilder sb = new StringBuilder();
                for (var e : now.long2IntEntrySet())
                    sb.append(BlockPos.getX(e.getLongKey())).append(' ').append(BlockPos.getZ(e.getLongKey())).append(' ').append(e.getIntValue()).append('\n');
                java.nio.file.Files.writeString(f, sb);
                report(src, "CR-STREAM-REF wrote " + now.size() + " stream columns near " + road.size() + " road columns to " + f);
                return;
            }
            Long2IntOpenHashMap ref = new Long2IntOpenHashMap();
            ref.defaultReturnValue(Integer.MIN_VALUE);
            for (String line : java.nio.file.Files.readAllLines(f)) {
                String[] t = line.trim().split(" ");
                if (t.length == 3) ref.put(BlockPos.asLong(Integer.parseInt(t[0]), 0, Integer.parseInt(t[1])), Integer.parseInt(t[2]));
            }
            int filled = 0, filledOther = 0, drained = 0, moved = 0, added = 0, same = 0, shown = 0, lowered = 0;
            for (var e : near.long2IntEntrySet()) {
                long key = e.getLongKey();
                int a = ref.get(key), b = now.get(key);
                boolean onRoad = roadTop.get(key) != Integer.MIN_VALUE;
                String what;
                if (a == b) {
                    if (a != Integer.MIN_VALUE) same++;
                    continue;
                } else if (b == Integer.MIN_VALUE) {
                    boolean ground = onRoad && (kinds.get(key) == EdgePlan.GROUND || kinds.get(key) == EdgePlan.CAUSEWAY);
                    what = ground ? "filled" : onRoad ? "underBridge" : "drained";
                    if (ground) filled++;
                    else if (onRoad) filledOther++;
                    else drained++;
                } else if (a == Integer.MIN_VALUE) {
                    what = "added";
                    added++;
                } else if (b == a - 1 && (onRoad || timberAt(level, p, BlockPos.getX(key), b + 1, BlockPos.getZ(key)))) {
                    // one down under a bridge deck or stringer: a ford row's bed lowered so the stream runs on under the planks
                    what = "lowered";
                    lowered++;
                } else {
                    what = "moved";
                    moved++;
                }
                if (shown++ < 60)
                    report(src, "CR-STREAM-DIFF " + what + " at " + BlockPos.getX(key) + " " + (b == Integer.MIN_VALUE ? a : b) + " " + BlockPos.getZ(key)
                        + " ref=" + (a == Integer.MIN_VALUE ? "-" : a) + " now=" + (b == Integer.MIN_VALUE ? "-" : b) + (onRoad ? " road top=" + roadTop.get(key) : ""));
            }
            report(src, "CR-STREAM-REF ref=" + ref.size() + " same=" + same + " filled=" + filled + " underBridge=" + filledOther + " drained=" + drained + " moved=" + moved + " lowered=" + lowered + " added=" + added
                + " intact=" + same + "/" + ref.size() + " (+lowered " + (same + lowered) + ")");
        } catch (java.io.IOException ex) {
            report(src, "CR-STREAM-REF error " + ex);
        }
    }

    /** Y of the column's topmost liquid between lo and hi (air, plants and leaves above it skipped), or MIN_VALUE. */
    private static int surfaceWater(ServerLevel level, BlockPos.MutableBlockPos p, int x, int z, int lo, int hi) {
        for (int y = hi; y >= lo; y--) {
            BlockState s = level.getBlockState(p.set(x, y, z));
            if (s.isAir()) continue;
            if (water(level, p)) return y;
            if (s.canBeReplaced() || s.is(net.minecraft.tags.BlockTags.LEAVES)) continue;
            // a boardwalk walkway piece laid on the water (RoadPainter.walkway): the stream runs on under it
            if (s.is(net.minecraft.world.level.block.Blocks.SPRUCE_SLAB) || s.is(net.minecraft.world.level.block.Blocks.SPRUCE_TRAPDOOR)
                || s.is(net.minecraft.world.level.block.Blocks.CAMPFIRE)) continue;
            return Integer.MIN_VALUE;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean water(ServerLevel level, BlockPos p) {
        FluidState f = level.getFluidState(p);
        return f.is(Fluids.WATER) || f.is(Fluids.FLOWING_WATER);
    }

    /** Water at y that the noise column does not have: another mod's stream or lake. */
    private static boolean stream(TerrainSampler terrain, int x, int y, int z) {
        TerrainSampler.Sample s = terrain.sample(x, z);
        return !s.wet() || y > s.water();
    }
}
