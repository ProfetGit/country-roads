package io.github.profetgit.countryroads.path;

/**
 * What the survey knows about a column: the ground the path may use, tree cost, and road bits. The search and the plan
 * read the world only through this, so they can run on a worker over captured chunks.
 */
interface Terrain {
    /** Not captured (yet): the chunk has to be fetched first. */
    int UNKNOWN = Integer.MIN_VALUE;
    /** The path may not use this column. */
    int CLOSED = -100000;
    /** Added to the water surface Y of a shallow-water column (a deck goes on top). */
    int WET = 100000;
    /** Added to the water surface Y of deep water (6 or more): the path cannot use it, but the bed beside may deck over it. */
    int DEEP = -1000000;

    /** Surface block Y the path may use, +WET for shallow water, CLOSED, or UNKNOWN when the chunk is not captured. */
    int ground(int x, int z);

    /** Leaves the path would cut above walkY and trunks beside the column: what PathSearch charges for going through. */
    int treeCost(int x, int z, int walkY);

    boolean road(int x, int z);

    boolean openRoad(int x, int z);

    /** Walking level of a ground value: one above the water for a deck. */
    static int level(int g) {
        return g >= WET ? g - WET + 1 : g;
    }

    static boolean deep(int g) {
        return g >= DEEP - 1000 && g <= DEEP + 1000;
    }

    static boolean usable(int g) {
        return g > -1000;
    }
}
