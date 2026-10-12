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

/** Players walk about 10% and horses about 15% faster while on a road. */
public final class RoadSpeed {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(CountryRoads.ID, "road_speed");
    static final double PLAYER = 0.10, MOUNT = 0.15;

    private RoadSpeed() {}

    public static void tick(ServerLevel level) {
        if ((level.getGameTime() & 3) != 0) return;
        for (ServerPlayer p : level.players()) {
            Entity v = p.getVehicle();
            if (v instanceof AbstractHorse horse) {
                apply(level, horse, MOUNT);
                apply(level, p, 0);
            } else {
                apply(level, p, p.isSpectator() || p.getAbilities().flying ? 0 : PLAYER);
            }
        }
    }

    static void apply(ServerLevel level, LivingEntity e, double amount) {
        AttributeInstance speed = e.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        boolean want = amount > 0 && onRoad(level, e);
        boolean has = speed.hasModifier(ID);
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
