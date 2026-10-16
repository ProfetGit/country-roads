package io.github.profetgit.countryroads.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Twice a second, asks the road network to plan ahead of the players (see {@link RoadNetwork#prefetch}). */
public final class Prefetch {
    /** Dev viewers (test walkers): {x, z, vx, vz}. */
    public static final List<double[]> EXTRA = new CopyOnWriteArrayList<>();

    private Prefetch() {}

    public static void tick(MinecraftServer server) {
        if (!RoadNetwork.PREFETCH_ON || server.getTickCount() % 10 != 0) return;
        ServerLevel level = server.overworld();
        RoadNetwork n = RoadNetwork.get(level);
        if (n == null) return;
        List<double[]> viewers = new ArrayList<>(EXTRA);
        for (ServerPlayer p : level.players()) {
            Vec3 v = p.getDeltaMovement();
            viewers.add(new double[]{p.getX(), p.getZ(), v.x * 20, v.z * 20});
        }
        n.prefetch(viewers);
    }
}
