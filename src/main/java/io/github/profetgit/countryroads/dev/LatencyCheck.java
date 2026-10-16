package io.github.profetgit.countryroads.dev;

import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.plan.RoadNetwork;
import java.util.Arrays;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * A player teleporting into new ground: a loading ticket (as a player's own) over a (2r+1)^2 square of chunks, polled
 * once per server tick until every chunk is full and visible. Reports when the first chunk, half and all of them
 * arrived, and how long the worldgen threads waited on road plans meanwhile. The plan caches are as warm as the run
 * left them: use far apart spots for cold measurements.
 */
final class LatencyCheck {
    private LatencyCheck() {}

    static int latency(CommandSourceStack src, int radius) {
        ServerLevel level = src.getLevel();
        BlockPos at = BlockPos.containing(src.getPosition());
        int cx = at.getX() >> 4, cz = at.getZ() >> 4, side = 2 * radius + 1, n = side * side;
        RoadNetwork road = RoadNetwork.get(level);
        ChunkPos center = new ChunkPos(cx, cz);
        long[] done = new long[n];
        long[] start = new long[2];
        int[] left = {n};
        boolean[] started = {false};
        DevCommands.queue(() -> {
            if (!started[0]) {
                started[0] = true;
                start[0] = System.nanoTime();
                start[1] = road == null ? 0 : road.waitNanos.get();
                level.getChunkSource().addTicketWithRadius(TicketType.PLAYER_LOADING, center, radius + 2);
                return false;
            }
            long now = System.nanoTime() - start[0];
            for (int i = 0; i < n; i++)
                if (done[i] == 0 && level.getChunkSource().getChunkNow(cx - radius + i % side, cz - radius + i / side) != null) {
                    done[i] = Math.max(1, now);
                    left[0]--;
                }
            if (left[0] > 0 && now < 600_000_000_000L) return false;
            level.getChunkSource().removeTicketWithRadius(TicketType.PLAYER_LOADING, center, radius + 2);
            long[] a = Arrays.stream(done).filter(x -> x > 0).sorted().toArray();
            long first = a.length == 0 ? -1 : a[0], half = a.length == 0 ? -1 : a[a.length / 2], last = a.length == 0 ? -1 : a[a.length - 1];
            long waited = road == null ? 0 : road.waitNanos.get() - start[1];
            String line = String.format("CR-LATENCY at %d,%d r=%d chunks=%d/%d firstMs=%d halfMs=%d allMs=%d genWaitMs=%d routed=%d",
                at.getX(), at.getZ(), radius, a.length, n, first / 1_000_000, half / 1_000_000, last / 1_000_000, waited / 1_000_000,
                road == null ? 0 : road.routed.get());
            CountryRoads.LOG.info(line);
            src.sendSuccess(() -> Component.literal(line), false);
            return true;
        });
        return 1;
    }

    /**
     * A virtual player walking from (x, z) along (dx, dz) at speed blocks per second for seconds seconds, with a loading
     * ticket of its own around it (view radius 8) and the road planner told about it as a viewer. Once a second it
     * counts the chunks within 3 of it that are not yet full: a second with any is a stall. Reports stall seconds, the
     * longest stall and the wait for the very first full ring.
     */
    static int walk(CommandSourceStack src, double dx, double dz, double speed, int seconds) {
        ServerLevel level = src.getLevel();
        double len = Math.hypot(dx, dz);
        double vx = dx / len * speed, vz = dz / len * speed;
        double[] viewer = {src.getPosition().x, src.getPosition().z, vx, vz};
        long[] t0 = {0, 0};
        java.util.List<Long> ticks = new java.util.ArrayList<>();
        int[] sec = {0}, stalls = {0}, run = {0}, longest = {0}, firstOk = {-1}, tick = {0};
        ChunkPos[] last = {null};
        boolean[] started = {false};
        RoadNetwork road = RoadNetwork.get(level);
        long wait0 = road == null ? 0 : road.waitNanos.get();
        DevCommands.queue(() -> {
            if (!started[0]) {
                started[0] = true;
                t0[0] = System.nanoTime();
                io.github.profetgit.countryroads.plan.Prefetch.EXTRA.add(viewer);
            }
            tick[0]++;
            long nowT = System.nanoTime();
            if (t0[1] != 0) ticks.add(nowT - t0[1]);
            t0[1] = nowT;
            viewer[0] += vx / 20;
            viewer[1] += vz / 20;
            ChunkPos here = new ChunkPos((int) Math.floor(viewer[0]) >> 4, (int) Math.floor(viewer[1]) >> 4);
            if (!here.equals(last[0])) {
                level.getChunkSource().addTicketWithRadius(TicketType.PLAYER_LOADING, here, 10);
                if (last[0] != null) level.getChunkSource().removeTicketWithRadius(TicketType.PLAYER_LOADING, last[0], 10);
                last[0] = here;
            }
            if (tick[0] % 20 != 0) return false;
            sec[0]++;
            int missing = 0;
            for (int ox = -3; ox <= 3; ox++)
                for (int oz = -3; oz <= 3; oz++) if (level.getChunkSource().getChunkNow(here.x() + ox, here.z() + oz) == null) missing++;
            if (missing == 0 && firstOk[0] < 0) firstOk[0] = sec[0];
            if (firstOk[0] >= 0) {
                if (missing > 0) {
                    stalls[0]++;
                    longest[0] = Math.max(longest[0], ++run[0]);
                } else run[0] = 0;
            }
            if (sec[0] < seconds) return false;
            level.getChunkSource().removeTicketWithRadius(TicketType.PLAYER_LOADING, last[0], 10);
            io.github.profetgit.countryroads.plan.Prefetch.EXTRA.remove(viewer);
            long[] dt = ticks.stream().mapToLong(Long::longValue).sorted().toArray();
            long p99 = dt.length == 0 ? 0 : dt[(int) Math.min(dt.length - 1, dt.length * 0.99)], worst = dt.length == 0 ? 0 : dt[dt.length - 1];
            String line = String.format("CR-WALK from %d,%d speed=%.0f seconds=%d firstRingS=%d stallSeconds=%d longestStallS=%d genWaitMs=%d ended=%d,%d tickP99Ms=%d tickMaxMs=%d",
                (int) src.getPosition().x, (int) src.getPosition().z, speed, seconds, firstOk[0], stalls[0], longest[0],
                road == null ? 0 : (road.waitNanos.get() - wait0) / 1_000_000, (int) viewer[0], (int) viewer[1], p99 / 1_000_000, worst / 1_000_000);
            CountryRoads.LOG.info(line);
            src.sendSuccess(() -> Component.literal(line), false);
            return true;
        });
        return 1;
    }
}
