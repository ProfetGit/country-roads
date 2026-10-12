package io.github.profetgit.countryroads.dev;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.world.TerrainSampler;
import io.github.profetgit.countryroads.world.VillageOracle;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

public final class DevCommands {
    /** Tick-sliced dev jobs; each returns true when finished. Run one per server tick so chunks can unload. */
    private static final java.util.ArrayDeque<java.util.function.BooleanSupplier> TASKS = new java.util.ArrayDeque<>();

    private DevCommands() {}

    public static void tick() {
        var task = TASKS.peek();
        if (task != null && task.getAsBoolean()) TASKS.poll();
    }

    public static void queue(java.util.function.BooleanSupplier task) {
        TASKS.add(task);
    }

    public static boolean busy() {
        return !TASKS.isEmpty();
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("countryroads")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(Commands.literal("villages")
                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 20000)).executes(DevCommands::villages)))
            .then(Commands.literal("oraclecheck")
                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 4000)).executes(DevCommands::oracleCheck)))
            .then(Commands.literal("pregen")
                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 4000)).executes(c -> RoadChecks.pregen(c.getSource(), IntegerArgumentType.getInteger(c, "radius")))))
            .then(Commands.literal("check")
                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 4000)).executes(c -> RoadChecks.check(c.getSource(), IntegerArgumentType.getInteger(c, "radius")))))
            .then(Commands.literal("roads")
                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 8000)).executes(c -> RoadChecks.roads(c.getSource(), IntegerArgumentType.getInteger(c, "radius")))))
            .then(Commands.literal("shots")
                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 4000)).executes(c -> {
                    CommandSourceStack src = c.getSource();
                    int r = IntegerArgumentType.getInteger(c, "radius");
                    queue(() -> {
                        RoadChecks.shots(src, r);
                        return true;
                    });
                    return 1;
                })))
            .then(Commands.literal("idle").executes(c -> {
                if (busy()) return 0;
                CountryRoads.LOG.info("CR-IDLE");
                return 1;
            }))
            .then(Commands.literal("sample")
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 100000)).executes(DevCommands::sample))));
    }

    private static int villages(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos at = BlockPos.containing(c.getSource().getPosition());
        int r = IntegerArgumentType.getInteger(c, "radius") >> 4;
        long t0 = System.nanoTime();
        VillageOracle oracle = new VillageOracle(level, Set.of());
        List<VillageOracle.Village> found = oracle.find((at.getX() >> 4) - r, (at.getZ() >> 4) - r, (at.getX() >> 4) + r, (at.getZ() >> 4) + r);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        for (var v : found) c.getSource().sendSuccess(() -> Component.literal(v.structure() + " at " + v.pos().toShortString()), false);
        String line = "CR-VILLAGES sets=" + oracle.setNames() + " found=" + found.size() + " ms=" + ms;
        CountryRoads.LOG.info(line);
        c.getSource().sendSuccess(() -> Component.literal(line), false);
        return found.size();
    }

    private static int oracleCheck(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos at = BlockPos.containing(c.getSource().getPosition());
        int r = IntegerArgumentType.getInteger(c, "radius") >> 4;
        int minX = (at.getX() >> 4) - r, minZ = (at.getZ() >> 4) - r, maxX = (at.getX() >> 4) + r, maxZ = (at.getZ() >> 4) + r;
        long t0 = System.nanoTime();
        VillageOracle oracle = new VillageOracle(level, Set.of());
        Set<String> predicted = new TreeSet<>();
        for (var v : oracle.find(minX, minZ, maxX, maxZ)) predicted.add(v.structure() + "@" + v.chunk().x() + "," + v.chunk().z());
        long predictMs = (System.nanoTime() - t0) / 1_000_000;

        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Set<String> actual = new TreeSet<>();
        long t1 = System.nanoTime();
        int side = 2 * r + 1;
        int total = side * side;
        int[] next = {0};
        CommandSourceStack src = c.getSource();
        TASKS.add(() -> {
            long until = System.nanoTime() + 40_000_000L;
            while (next[0] < total && System.nanoTime() < until) {
                int cx = minX + next[0] % side, cz = minZ + next[0] / side;
                next[0]++;
                ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.STRUCTURE_STARTS, true);
                if (chunk == null) continue;
                for (Map.Entry<Structure, StructureStart> e : chunk.getAllStarts().entrySet()) {
                    if (!e.getValue().isValid()) continue;
                    Holder<Structure> h = registry.wrapAsHolder(e.getKey());
                    if (!h.is(net.minecraft.tags.StructureTags.VILLAGE)) continue;
                    actual.add(registry.getKey(e.getKey()) + "@" + cx + "," + cz);
                }
            }
            if (next[0] < total) return false;
            long genMs = (System.nanoTime() - t1) / 1_000_000;
            Set<String> missing = new HashSet<>(actual);
            missing.removeAll(predicted);
            Set<String> extra = new HashSet<>(predicted);
            extra.removeAll(actual);
            String verdict = missing.isEmpty() && extra.isEmpty() ? "PASS" : "FAIL";
            String line = "CR-ORACLE " + verdict + " chunks=" + total + " predicted=" + predicted.size() + " actual=" + actual.size()
                + " missing=" + missing + " extra=" + extra + " predictMs=" + predictMs + " genMs=" + genMs;
            CountryRoads.LOG.info(line);
            src.sendSuccess(() -> Component.literal(line), false);
            return true;
        });
        return 1;
    }

    private static int sample(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        int n = IntegerArgumentType.getInteger(c, "count");
        TerrainSampler sampler = new TerrainSampler(level);
        RandomSource rnd = RandomSource.create(42);
        long sum = 0;
        int wet = 0, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            var s = sampler.sample(rnd.nextInt(20000) - 10000, rnd.nextInt(20000) - 10000);
            sum += s.ground();
            if (s.wet()) wet++;
            minY = Math.min(minY, s.ground());
            maxY = Math.max(maxY, s.ground());
        }
        double us = (System.nanoTime() - t0) / 1000.0 / n;
        String line = String.format("CR-SAMPLE n=%d usPerColumn=%.1f meanY=%.1f minY=%d maxY=%d wet=%d", n, us, sum / (double) n, minY, maxY, wet);
        CountryRoads.LOG.info(line);
        c.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }
}
