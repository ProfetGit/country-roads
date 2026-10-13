package io.github.profetgit.countryroads.path;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.server.level.ServerLevel;

/** Measurements of one survey for the stake test: wall time, server-thread time, chunks, expansions, route cost. */
public final class SurveyStats {
    public final long startNanos = System.nanoTime();
    public long endNanos, mainNanos, mainMaxNanos, allocBytes;
    public int ticks, chunksReq, chunksSync, expansions, candidates = 1, captured, waits;
    public long captureNanos, waitNanos;
    public long costDirect = -1, costFinal = -1;
    public final List<Long> tickTimes = new ArrayList<>();
    /** CR-STAKE-CAND lines: every candidate route the survey compared, and the winner. */
    public final List<String> candLines = java.util.Collections.synchronizedList(new ArrayList<>());
    public String outcome = "?";

    /** The stats of the survey that finished last (stake test reads it). */
    public static volatile SurveyStats last;
    /** The survey whose slice runs on the server thread right now, for {@link #touch}. */
    static SurveyStats active;

    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    static long allocated() {
        return THREADS.getCurrentThreadAllocatedBytes();
    }

    /** A read at (x, z) is about to happen: counts it when its chunk is not loaded (the read would load it while the tick waits). */
    static void touch(ServerLevel level, int x, int z) {
        if (active != null && level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) active.chunksSync++;
    }

    public String line() {
        long[] t = tickTimes.stream().mapToLong(Long::longValue).sorted().toArray();
        double p99 = t.length == 0 ? 0 : t[Math.min(t.length - 1, (int) Math.ceil(t.length * 0.99) - 1)] / 1e6;
        return String.format(Locale.ROOT, "CR-STAKE-PERF %s wallMs=%d mainMsSum=%.1f mainMsMax=%.2f ticks=%d tickP99=%.1f allocMB=%.1f chunksReq=%d chunksSync=%d expansions=%d candidates=%d captured=%d captureUs=%d waits=%d waitMs=%d",
            outcome, (endNanos - startNanos) / 1_000_000, mainNanos / 1e6, mainMaxNanos / 1e6, ticks, p99, allocBytes / 1048576.0, chunksReq, chunksSync, expansions, candidates, captured, captured == 0 ? 0 : captureNanos / 1000 / captured, waits, waitNanos / 1_000_000);
    }
}
