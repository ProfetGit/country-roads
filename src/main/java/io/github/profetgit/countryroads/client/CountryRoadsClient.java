package io.github.profetgit.countryroads.client;

import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.path.PathNotice;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Client side, loader-neutral: the stake path card beside the crosshair (the platform client entry wires these). */
public final class CountryRoadsClient {
    /** Dev: log each notice received and the first HUD layer call after it ({@code -Dcountryroads.traceNotices=true}). */
    private static final boolean TRACE = Boolean.getBoolean("countryroads.traceNotices");
    private static volatile boolean traceDraw;

    private CountryRoadsClient() {}

    /** A notice from the server, on the client thread. */
    public static void receive(PathNotice notice) {
        if (TRACE) {
            CountryRoads.LOG.info("CR-NOTICE-RECV code={} a={} thread={}", notice.code(), notice.a(), Thread.currentThread().getName());
            traceDraw = true;
        }
        PathHud.receive(notice);
    }

    public static void drawHud(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (traceDraw) {
            traceDraw = false;
            CountryRoads.LOG.info("CR-HUD-LAYER drawn");
        }
        PathHud.draw(g, delta);
    }
}
