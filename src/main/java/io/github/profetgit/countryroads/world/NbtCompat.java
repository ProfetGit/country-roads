package io.github.profetgit.countryroads.world;

import java.util.Optional;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/** NBT reads: Optional-returning getters on 26.x, plain getters (empty value when absent) on 1.21.1. */
public final class NbtCompat {
    private NbtCompat() {}

    public static CompoundTag compound(CompoundTag tag, String key) {
        //? if >=26.1 {
        return tag.getCompoundOrEmpty(key);
        //?} else {
        /*return tag.getCompound(key);
        *///?}
    }

    public static CompoundTag compound(ListTag list, int index) {
        //? if >=26.1 {
        return list.getCompoundOrEmpty(index);
        //?} else {
        /*return list.getCompound(index);
        *///?}
    }

    public static Optional<CompoundTag> optCompound(CompoundTag tag, String key) {
        //? if >=26.1 {
        return tag.getCompound(key);
        //?} else {
        /*return tag.contains(key, 10) ? Optional.of(tag.getCompound(key)) : Optional.empty();
        *///?}
    }

    public static ListTag list(CompoundTag tag, String key) {
        //? if >=26.1 {
        return tag.getListOrEmpty(key);
        //?} else {
        /*return tag.getList(key, 10);
        *///?}
    }

    public static int intOr(CompoundTag tag, String key, int fallback) {
        //? if >=26.1 {
        return tag.getIntOr(key, fallback);
        //?} else {
        /*return tag.contains(key, 99) ? tag.getInt(key) : fallback;
        *///?}
    }

    public static Optional<String> string(CompoundTag tag, String key) {
        //? if >=26.1 {
        return tag.getString(key);
        //?} else {
        /*return tag.contains(key, 8) ? Optional.of(tag.getString(key)) : Optional.empty();
        *///?}
    }

    public static String stringOr(CompoundTag tag, String key, String fallback) {
        return string(tag, key).orElse(fallback);
    }

    public static long[] longs(CompoundTag tag, String key) {
        //? if >=26.1 {
        return tag.getLongArray(key).orElse(new long[0]);
        //?} else {
        /*return tag.getLongArray(key);
        *///?}
    }

    public static Set<String> keys(CompoundTag tag) {
        //? if >=26.1 {
        return tag.keySet();
        //?} else {
        /*return tag.getAllKeys();
        *///?}
    }
}
