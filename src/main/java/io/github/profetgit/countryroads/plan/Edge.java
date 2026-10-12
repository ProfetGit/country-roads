package io.github.profetgit.countryroads.plan;

import io.github.profetgit.countryroads.world.VillageOracle.Village;

/** An undirected village pair, a before b by chunk key so the same pair always has the same orientation. */
public record Edge(Village a, Village b) {
    public static Edge of(Village p, Village q) {
        return Long.compare(p.key(), q.key()) <= 0 ? new Edge(p, q) : new Edge(q, p);
    }

    public double length() {
        return Math.hypot(a.pos().getX() - b.pos().getX(), a.pos().getZ() - b.pos().getZ());
    }

    public long seed() {
        return a.key() * 0x9E3779B97F4A7C15L ^ b.key();
    }
}
