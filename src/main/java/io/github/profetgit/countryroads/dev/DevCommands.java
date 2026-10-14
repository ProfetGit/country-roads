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

    /** Distance from (x, z) to the nearest planned open road column (plans within 832 blocks; waits for them). */
    private static int nearRoad(CommandSourceStack src, int x, int z) {
        var level = src.getLevel();
        var n = io.github.profetgit.countryroads.plan.RoadNetwork.get(level);
        if (n == null) return 0;
        java.util.Set<io.github.profetgit.countryroads.plan.Edge> edges = new java.util.LinkedHashSet<>();
        for (int rx = (x - 832) >> 9; rx <= (x + 832) >> 9; rx++)
            for (int rz = (z - 832) >> 9; rz <= (z + 832) >> 9; rz++) edges.addAll(n.edgesInRegion(rx, rz));
        java.util.List<java.util.concurrent.CompletableFuture<io.github.profetgit.countryroads.plan.EdgePlan>> fs = new java.util.ArrayList<>();
        for (var e : edges) fs.add(n.plan(e));
        queue(() -> {
            for (var f : fs) if (!f.isDone()) return false;
            double best = Double.MAX_VALUE;
            int bx = 0, bz = 0;
            for (var f : fs) {
                var p = f.join();
                if (p == null) continue;
                for (int i = 0; i < p.length(); i++) {
                    if (p.kind[i] == io.github.profetgit.countryroads.plan.EdgePlan.TUNNEL) continue;
                    double dd = Math.hypot(p.x[i] - x, p.z[i] - z);
                    if (dd < best) {
                        best = dd;
                        bx = (int) Math.floor(p.x[i]);
                        bz = (int) Math.floor(p.z[i]);
                    }
                }
            }
            CountryRoads.LOG.info("CR-NEARROAD " + x + "," + z + " dist=" + (int) best + " at=" + bx + "," + bz + " edges=" + fs.size());
            return true;
        });
        return 1;
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
            .then(Commands.literal("roadbiomes")
                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 8000)).executes(c -> RoadChecks.roadBiomes(c.getSource(), IntegerArgumentType.getInteger(c, "radius")))))
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
            .then(Commands.literal("cuts")
                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 4000)).executes(c -> CutCheck.cuts(c.getSource(), IntegerArgumentType.getInteger(c, "radius")))))
            .then(Commands.literal("streets")
                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 4000)).executes(c -> StreetChecks.streets(c.getSource(), IntegerArgumentType.getInteger(c, "radius")))))
            .then(Commands.literal("streetsnap")
                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 4000)).executes(c -> StreetChecks.snap(c.getSource(), IntegerArgumentType.getInteger(c, "radius")))))
            .then(Commands.literal("docks")
                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 4000)).executes(c -> StreetChecks.docks(c.getSource(), IntegerArgumentType.getInteger(c, "radius")))))
            .then(Commands.literal("streettops")
                .then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer())
                    .executes(c -> {
                        var src = c.getSource();
                        int x = IntegerArgumentType.getInteger(c, "x"), z = IntegerArgumentType.getInteger(c, "z");
                        queue(() -> {
                            if (io.github.profetgit.countryroads.retro.Retrofit.busy()) return false;
                            StreetChecks.tops(src, x, z);
                            return true;
                        });
                        return 1;
                    }))))
            .then(Commands.literal("roadspeed")
                .then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer())
                    .executes(c -> StreetChecks.roadSpeed(c.getSource(), IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"))))))
            .then(Commands.literal("decor")
                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 4000)).executes(c -> RoadChecks.decor(c.getSource(), IntegerArgumentType.getInteger(c, "radius")))))
            .then(Commands.literal("perks")
                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 4000)).executes(c -> RoadChecks.perks(c.getSource(), IntegerArgumentType.getInteger(c, "radius")))))
            .then(Commands.literal("inhabit")
                .then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer())
                    .executes(c -> {
                        var level = c.getSource().getLevel();
                        int x = IntegerArgumentType.getInteger(c, "x"), z = IntegerArgumentType.getInteger(c, "z");
                        var chunk = level.getChunk(x >> 4, z >> 4);
                        chunk.setInhabitedTime(io.github.profetgit.countryroads.retro.Retrofit.LIVED_IN * 2);
                        CountryRoads.LOG.info("CR-INHABIT chunk " + (x >> 4) + "," + (z >> 4));
                        return 1;
                    }))))
            .then(Commands.literal("box")
                .then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("y", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer())
                    .then(Commands.argument("r", IntegerArgumentType.integer(1, 8)).then(Commands.argument("dy", IntegerArgumentType.integer(1, 16))
                    .executes(c -> {
                        var level = c.getSource().getLevel();
                        int x = IntegerArgumentType.getInteger(c, "x"), y = IntegerArgumentType.getInteger(c, "y"), z = IntegerArgumentType.getInteger(c, "z");
                        int r = IntegerArgumentType.getInteger(c, "r"), dy = IntegerArgumentType.getInteger(c, "dy");
                        // one line per layer, top down: rows of z, columns of x; legend after
                        java.util.Map<String, Character> legend = new java.util.LinkedHashMap<>();
                        String keys = ".abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
                        var m = io.github.profetgit.countryroads.plan.RoadNetwork.get(level).mask(x >> 4, z >> 4);
                        StringBuilder out = new StringBuilder("CR-BOX " + x + "," + y + "," + z + " r=" + r);
                        for (int yy = y + dy; yy >= y - dy; yy--) {
                            out.append("\nCR-BOX y=").append(yy).append(" ");
                            for (int zz = z - r; zz <= z + r; zz++) {
                                out.append(zz == z - r ? "" : "|");
                                for (int xx = x - r; xx <= x + r; xx++) {
                                    var st = level.getBlockState(new net.minecraft.core.BlockPos(xx, yy, zz));
                                    String id = st.isAir() ? "air" : st.getBlock().getDescriptionId().replace("block.minecraft.", "");
                                    Character ch = legend.get(id);
                                    if (ch == null) {
                                        ch = keys.charAt(Math.min(keys.length() - 1, legend.size()));
                                        legend.put(id, ch);
                                    }
                                    out.append(ch);
                                }
                            }
                        }
                        out.append("\nCR-BOX legend ").append(legend);
                        StringBuilder ins = new StringBuilder("\nCR-BOX in(z rows) ");
                        var n = io.github.profetgit.countryroads.plan.RoadNetwork.get(level);
                        for (int zz = z - r; zz <= z + r; zz++) {
                            ins.append(zz == z - r ? "" : "|");
                            for (int xx = x - r; xx <= x + r; xx++) {
                                var mm = n.mask(xx >> 4, zz >> 4);
                                float v = mm.in[io.github.profetgit.countryroads.plan.ChunkMask.idx(xx & 15, zz & 15)];
                                ins.append(v == Float.NEGATIVE_INFINITY ? "_" : v >= 0 ? "R" : String.valueOf(Math.min(9, (int) -v)));
                            }
                        }
                        out.append(ins);
                        CountryRoads.LOG.info(out.toString());
                        return 1;
                    })))))))
            .then(Commands.literal("column")
                .then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer())
                    .executes(c -> {
                        var level = c.getSource().getLevel();
                        int x = IntegerArgumentType.getInteger(c, "x"), z = IntegerArgumentType.getInteger(c, "z");
                        var n = io.github.profetgit.countryroads.plan.RoadNetwork.get(level);
                        var m = n.mask(x >> 4, z >> 4);
                        int i = io.github.profetgit.countryroads.plan.ChunkMask.idx(x & 15, z & 15);
                        int top = (int) Math.floor(m.surface(i));
                        var biome = level.getBiome(new net.minecraft.core.BlockPos(x, top, z));
                        StringBuilder sb = new StringBuilder("CR-COLUMN " + x + "," + z + " in=" + m.in[i] + " y=" + m.y[i] + " kind=" + m.kind[i]
                            + " biome=" + biome.unwrapKey().map(k -> k.identifier().toString()).orElse("?") + " palette=" + io.github.profetgit.countryroads.paint.Palette.surface(biome).name() + " :");
                        for (int y = top + 4; y >= top - 8; y--)
                            sb.append(" ").append(y).append("=").append(level.getBlockState(new net.minecraft.core.BlockPos(x, y, z)).getBlock().getDescriptionId().replace("block.minecraft.", ""));
                        CountryRoads.LOG.info(sb.toString());
                        return 1;
                    }))))
            .then(Commands.literal("staketest")
                .then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer())
                    .executes(c -> StakeTest.run(c.getSource(), IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"), false))
                    .then(Commands.literal("keep")
                        .executes(c -> StakeTest.run(c.getSource(), IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"), true)))
                    .then(Commands.literal("warm")
                        .executes(c -> StakeTest.run(c.getSource(), IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"), false, Integer.MIN_VALUE, 0, StakeTest.WARM)))
                    .then(Commands.literal("door")
                        .executes(c -> {
                            StakeTest.doorMode = true;
                            return StakeTest.run(c.getSource(), IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"), false, Integer.MIN_VALUE, 0, StakeTest.WARM);
                        }))
                    .then(Commands.literal("house")
                        .executes(c -> {
                            StakeTest.doorMode = true;
                            StakeTest.houseMode = 1;
                            return StakeTest.run(c.getSource(), IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"), false, Integer.MIN_VALUE, 0, StakeTest.WARM);
                        }))
                    .then(Commands.literal("houseyard")
                        .executes(c -> {
                            StakeTest.doorMode = true;
                            StakeTest.houseMode = 2;
                            return StakeTest.run(c.getSource(), IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"), false, Integer.MIN_VALUE, 0, StakeTest.WARM);
                        }))
                    .then(Commands.literal("housewalls")
                        .executes(c -> {
                            StakeTest.doorMode = true;
                            StakeTest.houseMode = 3;
                            return StakeTest.run(c.getSource(), IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"), false, Integer.MIN_VALUE, 0, StakeTest.WARM);
                        }))
                    .then(Commands.literal("housesealed")
                        .executes(c -> {
                            StakeTest.doorMode = true;
                            StakeTest.houseMode = 4;
                            return StakeTest.run(c.getSource(), IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"), false, Integer.MIN_VALUE, 0, StakeTest.WARM);
                        }))
                    .then(Commands.literal("doorkeep")
                        .executes(c -> {
                            StakeTest.doorMode = true;
                            StakeTest.commitMode = true;
                            return StakeTest.run(c.getSource(), IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"), false, Integer.MIN_VALUE, 0, StakeTest.WARM);
                        }))
                    .then(Commands.literal("cancel")
                        .executes(c -> StakeTest.special(c.getSource(), IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"), "cancel")))
                    .then(Commands.literal("multi")
                        .executes(c -> StakeTest.special(c.getSource(), IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"), "multi")))
                    .then(Commands.literal("cold")
                        .executes(c -> StakeTest.run(c.getSource(), IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"), false, Integer.MIN_VALUE, 0, StakeTest.COLD)))
                    .then(Commands.literal("via").then(Commands.argument("vx", IntegerArgumentType.integer()).then(Commands.argument("vz", IntegerArgumentType.integer())
                        .executes(c -> StakeTest.run(c.getSource(), IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"), false,
                            IntegerArgumentType.getInteger(c, "vx"), IntegerArgumentType.getInteger(c, "vz")))
                        .then(Commands.argument("wx", IntegerArgumentType.integer()).then(Commands.argument("wz", IntegerArgumentType.integer())
                            .executes(c -> {
                                StakeTest.via2 = new int[]{IntegerArgumentType.getInteger(c, "wx"), IntegerArgumentType.getInteger(c, "wz")};
                                return StakeTest.run(c.getSource(), IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"), false,
                                    IntegerArgumentType.getInteger(c, "vx"), IntegerArgumentType.getInteger(c, "vz"), StakeTest.WARM);
                            })))))))))
            .then(Commands.literal("nearroad")
                .then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer())
                    .executes(c -> nearRoad(c.getSource(), IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"))))))
            .then(Commands.literal("kittree")
                .then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer())
                    .then(Commands.argument("dir", IntegerArgumentType.integer(0, 3)).executes(c -> {
                        int x = IntegerArgumentType.getInteger(c, "x"), z = IntegerArgumentType.getInteger(c, "z"), dr = IntegerArgumentType.getInteger(c, "dir");
                        var level = c.getSource().getLevel();
                        queue(() -> {
                            for (int cx = (x >> 4) - 3; cx <= (x >> 4) + 3; cx++)
                                for (int cz = (z >> 4) - 3; cz <= (z >> 4) + 3; cz++) level.getChunk(cx, cz);
                            io.github.profetgit.countryroads.CountryRoads.LOG.info(io.github.profetgit.countryroads.path.HousePaths.devKitTree(level, x, z, dr));
                            return true;
                        });
                        return 1;
                    })))))
            .then(Commands.literal("kitwoods").executes(c -> {
                io.github.profetgit.countryroads.CountryRoads.LOG.info(io.github.profetgit.countryroads.path.BridgeKit.woodCheck());
                return 1;
            }))
            .then(Commands.literal("capturebench")
                .then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer())
                    .executes(c -> {
                        int x = IntegerArgumentType.getInteger(c, "x"), z = IntegerArgumentType.getInteger(c, "z");
                        var level = c.getSource().getLevel();
                        queue(() -> {
                            for (int cx = (x >> 4) - 8; cx <= (x >> 4) + 8; cx++)
                                for (int cz = (z >> 4) - 8; cz <= (z >> 4) + 8; cz++)
                                    if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                                        level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PLAYER_LOADING, new net.minecraft.world.level.ChunkPos(cx, cz), 0);
                                        return false;
                                    }
                            CountryRoads.LOG.info(io.github.profetgit.countryroads.path.HousePaths.captureBench(level, x, z, 128));
                            CountryRoads.LOG.info(io.github.profetgit.countryroads.path.HousePaths.snapCheck(level, x, z, 120));
                            return true;
                        });
                        return 1;
                    }))))
            .then(Commands.literal("boresafe")
                .then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer())
                    .then(Commands.argument("d", IntegerArgumentType.integer(0, 3)).then(Commands.argument("len", IntegerArgumentType.integer(1, 64))
                        .executes(c -> {
                            int x = IntegerArgumentType.getInteger(c, "x"), z = IntegerArgumentType.getInteger(c, "z");
                            int dir = IntegerArgumentType.getInteger(c, "d"), len = IntegerArgumentType.getInteger(c, "len");
                            var level = c.getSource().getLevel();
                            queue(() -> {
                                for (int cx = (x >> 4) - 2; cx <= (x >> 4) + 2; cx++)
                                    for (int cz = (z >> 4) - 2; cz <= (z >> 4) + 5; cz++)
                                        if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                                            level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PLAYER_LOADING, new net.minecraft.world.level.ChunkPos(cx, cz), 0);
                                            return false;
                                        }
                                CountryRoads.LOG.info(io.github.profetgit.countryroads.path.HousePaths.boreSafe(level, x, z, dir, len));
                                return true;
                            });
                            return 1;
                        }))))))
            .then(Commands.literal("surveyweight")
                .then(Commands.argument("w", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0, 5)).executes(c -> {
                    io.github.profetgit.countryroads.path.HousePaths.surveyWeight(com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(c, "w"));
                    return 1;
                })))
            .then(Commands.literal("borecost")
                .then(Commands.argument("per", IntegerArgumentType.integer(0, 200)).then(Commands.argument("fixed", IntegerArgumentType.integer(0, 1000)).executes(c -> {
                    io.github.profetgit.countryroads.path.HousePaths.boreCost(IntegerArgumentType.getInteger(c, "per"), IntegerArgumentType.getInteger(c, "fixed"));
                    return 1;
                }))))
            .then(Commands.literal("retroresume").executes(c -> {
                io.github.profetgit.countryroads.retro.Retrofit.paused = false;
                return 1;
            }))
            .then(Commands.literal("inhabitroads")
                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 4000)).executes(c -> {
                    var level = c.getSource().getLevel();
                    var n = io.github.profetgit.countryroads.plan.RoadNetwork.get(level);
                    var at = net.minecraft.core.BlockPos.containing(c.getSource().getPosition());
                    int r = IntegerArgumentType.getInteger(c, "radius") >> 4, marked = 0;
                    for (int cx = (at.getX() >> 4) - r; cx <= (at.getX() >> 4) + r && marked < 3; cx += 5)
                        for (int cz = (at.getZ() >> 4) - r; cz <= (at.getZ() >> 4) + r && marked < 3; cz++)
                            if (n.mask(cx, cz).any()) {
                                level.getChunk(cx, cz).setInhabitedTime(io.github.profetgit.countryroads.retro.Retrofit.LIVED_IN * 2);
                                CountryRoads.LOG.info("CR-INHABIT chunk " + cx + "," + cz);
                                marked++;
                                cz += 6;
                            }
                    return marked;
                })))
            .then(Commands.literal("retrostats").executes(c -> {
                queue(() -> {
                    if (io.github.profetgit.countryroads.retro.Retrofit.busy()) return false;
                    var R = io.github.profetgit.countryroads.retro.Retrofit.class;
                    CountryRoads.LOG.info("CR-RETRO queued=" + io.github.profetgit.countryroads.retro.Retrofit.queued + " noRoad=" + io.github.profetgit.countryroads.retro.Retrofit.plain
                        + " painted=" + io.github.profetgit.countryroads.retro.Retrofit.painted + " livedIn=" + io.github.profetgit.countryroads.retro.Retrofit.skippedLivedIn
                        + " streetColumns=" + io.github.profetgit.countryroads.retro.Retrofit.streetsPainted + " crafted=" + io.github.profetgit.countryroads.retro.Retrofit.skippedCrafted + " craftedBlocks=" + io.github.profetgit.countryroads.retro.Retrofit.craftedSeen);
                    return true;
                });
                return 1;
            }))
            .then(Commands.literal("idle").executes(c -> {
                if (busy() || io.github.profetgit.countryroads.retro.Retrofit.busy()) return 0;
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
