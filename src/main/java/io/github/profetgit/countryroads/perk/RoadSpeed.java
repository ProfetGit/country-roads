package io.github.profetgit.countryroads.perk;

import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.world.RoadColumns;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.AbstractHorse;

/** On a road players walk faster (+20% by default, like Speed I) and mounts (horses, donkeys, mules, camels...) +30%. */
public final class RoadSpeed {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(CountryRoads.ID, "road_speed");

    private RoadSpeed() {}

    public static void tick(ServerLevel level) {
        if ((level.getGameTime() & 3) != 0) return;
        var c = io.github.profetgit.countryroads.Config.get();
        double player = c.playerSpeed / 100.0, mount = c.mountSpeed / 100.0;
        for (ServerPlayer p : level.players()) {
            Entity v = p.getVehicle();
            if (v instanceof AbstractHorse horse) {
                apply(level, horse, mount);
                apply(level, p, 0);
            } else {
                apply(level, p, p.isSpectator() || p.getAbilities().flying ? 0 : player);
            }
        }
    }

    public static void apply(ServerLevel level, LivingEntity e, double amount) {
        AttributeInstance speed = e.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        boolean want = amount > 0 && onRoad(level, e);
        AttributeModifier old = speed.getModifier(ID);
        if (old != null && want && old.amount() != amount) {
            speed.removeModifier(ID);
            old = null;
        }
        boolean has = old != null;
        if (want && !has) speed.addTransientModifier(new AttributeModifier(ID, amount, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        else if (!want && has) speed.removeModifier(ID);
    }

    /** Standing on (or hopping just above) a road column. */
    static boolean onRoad(ServerLevel level, Entity e) {
        BlockPos below = BlockPos.containing(e.getX(), e.getY() - 0.6, e.getZ());
        if (!RoadColumns.isRoad(level, below.getX(), below.getZ())) return false;
        for (int k = 0; k < 3; k++) if (!level.getBlockState(below.below(k)).isAir()) return true;
        return false;
    }
}
