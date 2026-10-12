package io.github.profetgit.countryroads.plan;

import io.github.profetgit.countryroads.paint.Palette;

/** Village names from the village's position, flavoured by its biome. Deterministic. */
public final class NameGen {
    private static final String[][] PRE = {
        {"Ash", "Oak", "Mill", "Stone", "Thorn", "Bright", "Elder", "Haw", "Fern", "Wil", "Barley", "Hollin", "Brack", "Ember", "Lark", "Wren", "Clover", "Apple", "Kings", "Bram"},
        {"Pine", "Fir", "Bear", "Moss", "Raven", "Elk", "Cold", "Grey", "Holt", "Wolf"},
        {"Frost", "Rime", "Winter", "Ice", "White", "Snow", "Hail", "Tundra"},
        {"Sand", "Sun", "Amber", "Dune", "Gold", "Ochre", "Mirage", "Salt"},
        {"Red", "Rust", "Copper", "Mesa", "Clay", "Ochre"},
        {"Acacia", "Lion", "Dust", "Tawny", "Sunfield", "Thorn"},
        {"Marsh", "Reed", "Fen", "Mire", "Willow", "Heron", "Bog"},
        {"Vine", "Fern", "Parrot", "Canopy", "Emerald", "Lush"},
        {"Crag", "Flint", "Scree", "High", "Cairn", "Tor"}};
    private static final String[][] SUF = {
        {"ford", "wick", "by", "ton", "stead", "dale", "brook", "field", "holm", "well", "bury", "ley", "cote", "hurst", "mere"},
        {"hold", "fell", "wood", "ridge", "shaw", "gill"},
        {"hold", "fell", "mere", "watch", "haven", "reach"},
        {"reach", "well", "haven", "spring", "rest", "gate"},
        {"rock", "gulch", "well", "ridge", "mesa"},
        {"veld", "rest", "well", "plain", "kraal"},
        {"wick", "fen", "mere", "holm", "lake", "moss"},
        {"grove", "glade", "hollow", "falls", "nest"},
        {"crest", "fell", "pass", "stead", "watch"}};

    private NameGen() {}

    public static String name(long villageKey, Palette.Surface biome) {
        int style = switch (biome.name()) {
            case "taiga" -> 1;
            case "snowy" -> 2;
            case "desert" -> 3;
            case "badlands" -> 4;
            case "savanna" -> 5;
            case "swamp" -> 6;
            case "jungle" -> 7;
            case "stony" -> 8;
            default -> 0;
        };
        long h = villageKey * 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        String[] pre = (h & 3) == 0 ? PRE[0] : PRE[style];
        String[] suf = ((h >>> 2) & 3) == 0 ? SUF[0] : SUF[style];
        String a = pre[(int) Math.floorMod(h >>> 8, (long) pre.length)];
        String b = suf[(int) Math.floorMod(h >>> 24, (long) suf.length)];
        if (a.endsWith(b.substring(0, 1))) b = suf[(int) Math.floorMod((h >>> 24) + 1, (long) suf.length)];
        return a + b;
    }
}
