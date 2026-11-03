package io.github.profetgit.countryroads.world;

import io.github.profetgit.countryroads.world.RoadColumns;

/** NeoForge and Forge: a chunk (or a chunk's saved data) carrying its road columns, added by these mixins. */
public interface RoadColumnsHolder {
    RoadColumns countryroads$cols();

    void countryroads$setCols(RoadColumns cols);
}
