package io.github.profetgit.countryroads.world;

import com.mojang.serialization.Codec;
import io.github.profetgit.countryroads.CountryRoads;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/** Per-dimension codec-backed saved data: SavedDataType on 26.x, SavedData.Factory + a CompoundTag on 1.21.1. */
public final class SavedDataCompat {
    //? if >=26.1 {
    /** One type per dimension, cached: the storage keys by the type record. */
    private static final Map<String, net.minecraft.world.level.saveddata.SavedDataType<?>> TYPES = new ConcurrentHashMap<>();
    //?}

    private SavedDataCompat() {}

    @SuppressWarnings("unchecked")
    public static <T extends SavedData> T of(ServerLevel level, String prefix, Codec<T> codec, Supplier<T> fresh) {
        String name = prefix + level.dimension().identifier().toString().replace(':', '_').replace('/', '_');
        //? if >=26.1 {
        var type = (net.minecraft.world.level.saveddata.SavedDataType<T>) TYPES.computeIfAbsent(name,
            n -> new net.minecraft.world.level.saveddata.SavedDataType<>(net.minecraft.resources.Identifier.fromNamespaceAndPath(CountryRoads.ID, n), fresh, codec,
                DataFixTypes.SAVED_DATA_COMMAND_STORAGE));
        return level.getDataStorage().computeIfAbsent(type);
        //?} else if >=1.21 {
        /*return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(fresh, (tag, provider) -> {
            net.minecraft.nbt.Tag v = tag.get("v");
            if (v == null) return fresh.get();
            return codec.parse(provider.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), v).result().orElseGet(fresh);
        }, DataFixTypes.SAVED_DATA_COMMAND_STORAGE), CountryRoads.ID + "_" + name);
        *///?} else {
        /*return level.getDataStorage().computeIfAbsent(tag -> {
            net.minecraft.nbt.Tag v = tag.get("v");
            if (v == null) return fresh.get();
            return codec.parse(net.minecraft.nbt.NbtOps.INSTANCE, v).result().orElseGet(fresh);
        }, fresh, CountryRoads.ID + "_" + name);
        *///?}
    }

    //? if <26.1 {
    /*public static <T> net.minecraft.nbt.CompoundTag save(Codec<T> codec, T value, net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider provider) {
        codec.encodeStart(provider.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), value).result().ifPresent(v -> tag.put("v", v));
        return tag;
    }
    *///?}
}
