package io.github.profetgit.countryroads.path;

import io.github.profetgit.countryroads.CountryRoads;
import io.github.profetgit.countryroads.plan.Edge;
import io.github.profetgit.countryroads.plan.EdgePlan;
import io.github.profetgit.countryroads.plan.RoadNetwork;
import io.github.profetgit.countryroads.world.RoadColumns;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The survey of one stake. The route is searched on a worker thread over captured columns (RouteSolver), so the
 * server thread only does what must happen there: fetch chunks (one batch of tickets, polled without waiting, never a
 * synchronous load), capture them into the snapshot within a small budget, and check the rock of proposed tunnels.
 *
 * The worker first learns where the roads are without exploring: open-road columns of loaded chunks, the planned
 * centrelines of the road network (known from the seed before their chunks exist) and the index of built house
 * paths. Then A* runs toward them. For the last leg several candidates are searched (the nearest road, other nearby
 * roads, a route kept apart from the first, one over the hill instead of through it) and the best by cost, stairs and
 * bill wins; only that one gets the wandering pass and is shown. The route is the house path's own (see RouteSolver).
 */
final class PathSearch implements RouteSolver.Host, Runnable {
    static final int RADIUS = RouteSolver.RADIUS, TICKET_EVERY = 100, MAX_IN_FLIGHT = 48, CHUNK_TIMEOUT = 1200, PLAN_WAIT_MS = 1500, PLAN_WAIT_LONG_MS = 20000;
    static final int MAX_CANDIDATES = 4, PENALTY = 15, EST = 20;
    /** Coarse planning when more chunks than this on the line to the nearest road are not loaded; corridor half-widths in 8-block cells. */
    static final int COARSE_ABOVE = 6, CORRIDOR = 3, CORRIDOR_WIDE = 8, COARSE_ROUTES = 3;
    /** Keeps fetched chunks loaded while a survey runs; not saved with the world, expires if a survey forgets it. */
    static final TicketType TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath(CountryRoads.ID, "survey"),
        new TicketType(400L, TicketType.FLAG_LOADING));
    /** Workers allowed to compute at once (a waiting worker gives its turn away). */
    private static final Semaphore COMPUTE = new Semaphore(Runtime.getRuntime().availableProcessors() >= 8 ? 2 : 1);
    private static final AtomicInteger IDS = new AtomicInteger();
    /** Survey tickets currently held, all surveys (the stake test checks that a cancelled survey left none). */
    static int held;

    /** Registers the ticket type (mod init, before the registries freeze). */
    static void init() {
    }

    final ServerLevel level;
    final BlockPos stake;
    final ServerPlayer player;
    final int sx, sz, stakeY;
    final long doorKey;
    final SurveyStats stats = new SurveyStats();
    private final List<Long> waypoints = new ArrayList<>();
    private final long[] loadedGoals, pathGoals;
    /** Chunks loaded when the survey began (the coarse corridor never excludes them). */
    private final LongOpenHashSet loadedAtStart = new LongOpenHashSet();
    private final ColumnSnapshot snap = new ColumnSnapshot();
    private final ColumnSnapshot.View view;
    private final int minY;

    // outcome: written by the worker before done, read on the server thread after
    HousePaths.Survey survey;
    /** Set when the search ended without a route: the translation key of the player's message. */
    String failure;
    int failureArg;
    private volatile boolean done, cancelled;
    /** Bores of the chosen route (checked again on the server thread), and bores found unsafe there (kept for a new search). */
    private List<RouteSolver.Bore> finalBores = List.of();
    private final LongOpenHashSet unsafe = new LongOpenHashSet();
    private boolean holding, revalidated;
    private int retries;

    // worker -> server thread
    private final ConcurrentLinkedQueue<long[]> wanted = new ConcurrentLinkedQueue<>();
    /** Chunks to capture if they happen to be loaded (never fetched). */
    private final ConcurrentLinkedQueue<long[]> looking = new ConcurrentLinkedQueue<>();
    /** Chunks the worker asked to load (for the candidate lines). */
    private final AtomicInteger loads = new AtomicInteger();
    private final ConcurrentLinkedQueue<Waiter> waiters = new ConcurrentLinkedQueue<>();

    // server thread only
    private final LongLinkedOpenHashSet toTicket = new LongLinkedOpenHashSet();
    private final Long2IntOpenHashMap pending = new Long2IntOpenHashMap();
    private final LongOpenHashSet ticketed = new LongOpenHashSet();
    private final LongArrayFIFOQueue toCapture = new LongArrayFIFOQueue();
    /** Loaded chunks to capture whenever the budget allows, before the worker asks for them. */
    private final LongArrayFIFOQueue ahead = new LongArrayFIFOQueue();
    private final LongOpenHashSet capturing = new LongOpenHashSet();
    private final List<Waiter> waiting = new ArrayList<>();
    private int age;

    private record Waiter(long[] chunks, boolean load, LongOpenHashSet absent, CompletableFuture<long[]> done) {}

    PathSearch(ServerLevel level, BlockPos stake, ServerPlayer player, List<BlockPos> waypointPos) {
        this.level = level;
        this.stake = stake;
        this.player = player;
        this.sx = stake.getX();
        this.sz = stake.getZ();
        this.stakeY = stake.getY();
        this.doorKey = RouteSolver.key(sx, sz);
        this.minY = level.getMinY();
        List<long[]> overrides = new ArrayList<>();
        overrides.add(new long[]{doorKey, stakeY - 1});
        for (BlockPos w : waypointPos) {
            waypoints.add(RouteSolver.key(w.getX(), w.getZ()));
            overrides.add(new long[]{RouteSolver.key(w.getX(), w.getZ()), w.getY() - 1});
        }
        this.view = new ColumnSnapshot.View(snap, sx, sz, RADIUS, overrides);
        this.view.minY = minY;
        // roads already standing in loaded chunks: read straight from their road bits
        LongArrayList found = new LongArrayList(), loadedChunks = new LongArrayList(), goalChunks = new LongArrayList();
        int cr = (RADIUS >> 4) + 1;
        for (int cx = (sx >> 4) - cr; cx <= (sx >> 4) + cr; cx++)
            for (int cz = (sz >> 4) - cr; cz <= (sz >> 4) + cr; cz++) {
                LevelChunk c = level.getChunkSource().getChunkNow(cx, cz);
                if (c == null) continue;
                loadedChunks.add(ChunkPos.pack(cx, cz));
                loadedAtStart.add(ChunkPos.pack(cx, cz));
                RoadColumns rc = ((AttachmentTarget) c).getAttached(RoadColumns.TYPE);
                if (rc == null || !rc.any()) continue;
                goalChunks.add(ChunkPos.pack(cx, cz));
                for (int lz = 0; lz < 16; lz++)
                    for (int lx = 0; lx < 16; lx++) {
                        int x = (cx << 4) + lx, z = (cz << 4) + lz;
                        if (rc.open(lx, lz) && Math.max(Math.abs(x - sx), Math.abs(z - sz)) <= RADIUS) found.add(RouteSolver.key(x, z));
                    }
            }
        this.loadedGoals = found.toLongArray();
        // loaded chunks are captured ahead of the search, those between the door and the nearest road first
        long[] order = loadedChunks.toLongArray();
        long[] prio = new long[order.length];
        for (int i = 0; i < order.length; i++) {
            int cx = ChunkPos.getX(order[i]), cz = ChunkPos.getZ(order[i]);
            int fromDoor = Math.abs(cx - (sx >> 4)) + Math.abs(cz - (sz >> 4)), toRoad = 0;
            if (!goalChunks.isEmpty()) {
                toRoad = Integer.MAX_VALUE;
                for (long g : goalChunks) toRoad = Math.min(toRoad, Math.abs(cx - ChunkPos.getX(g)) + Math.abs(cz - ChunkPos.getZ(g)));
            }
            prio[i] = ((long) (fromDoor + toRoad) << 32) | i;
        }
        Arrays.sort(prio);
        for (long p : prio) ahead.enqueue(order[(int) p]);
        this.pathGoals = HousePathIndex.of(level).within(sx, sz, RADIUS);
        // the chunks around the door are needed first: captured now, so the worker starts searching at once
        for (int cx = (sx >> 4) - 2; cx <= (sx >> 4) + 2; cx++)
            for (int cz = (sz >> 4) - 2; cz <= (sz >> 4) + 2; cz++) {
                LevelChunk c = level.getChunkSource().getChunkNow(cx, cz);
                if (c != null) snap.put(ChunkPos.pack(cx, cz), c, minY);
            }
        Thread.ofVirtual().name("CountryRoads-survey-" + IDS.incrementAndGet()).start(this);
    }

    // ---------------------------------------------------------------- server thread

    /** Cancels the survey (stake broken, surveyed again, server stopping): tickets go, a late result is dropped. */
    void cancel() {
        cancelled = true;
        release();
    }

    private void release() {
        for (long ch : ticketed) level.getChunkSource().removeTicketWithRadius(TICKET, new ChunkPos(ChunkPos.getX(ch), ChunkPos.getZ(ch)), 0);
        held -= ticketed.size();
        ticketed.clear();
        pending.clear();
        toTicket.clear();
    }

    private void ticket(long ch) {
        level.getChunkSource().addTicketWithRadius(TICKET, new ChunkPos(ChunkPos.getX(ch), ChunkPos.getZ(ch)), 0);
    }

    private boolean loaded(long ch) {
        return level.getChunkSource().getChunkNow(ChunkPos.getX(ch), ChunkPos.getZ(ch)) != null;
    }

    /** One slice on the server thread; true when the survey is over (see survey / failure). */
    boolean step(long until) {
        if (cancelled) return true;
        if (age++ % TICKET_EVERY == 0) for (long ch : ticketed) ticket(ch);
        for (long[] w; (w = wanted.poll()) != null; ) for (long ch : w) want(ch, false);
        for (long[] w; (w = looking.poll()) != null; ) for (long ch : w) if (loaded(ch)) want(ch, false);
        for (Waiter w; (w = waiters.poll()) != null; ) {
            waiting.add(w);
            for (long ch : w.chunks) {
                if (w.load || loaded(ch) || pending.containsKey(ch) || toTicket.contains(ch)) want(ch, true);
                else if (!snap.chunks.containsKey(ch)) w.absent.add(ch);
            }
        }
        // tickets in one batch, a capped number in flight; the ones a worker waits for first
        for (Iterator<Long> it = toTicket.iterator(); it.hasNext() && pending.size() < MAX_IN_FLIGHT; ) {
            long ch = it.next();
            it.remove();
            if (ticketed.add(ch)) {
                ticket(ch);
                stats.chunksReq++;
                held++;
            }
            pending.put(ch, age);
        }
        for (var it = pending.long2IntEntrySet().fastIterator(); it.hasNext(); ) {
            var e = it.next();
            long ch = e.getLongKey();
            if (loaded(ch)) {
                it.remove();
                if (capturing.add(ch)) toCapture.enqueue(ch);
            } else if (age - e.getIntValue() > CHUNK_TIMEOUT) {
                // never came: its columns read as closed
                it.remove();
                snap.chunks.put(ch, ColumnSnapshot.Chunk.MISSING);
            }
        }
        long t0 = System.nanoTime();
        int n0 = stats.captured;
        while (!toCapture.isEmpty() && System.nanoTime() < until) {
            long ch = toCapture.dequeueLong();
            LevelChunk c = level.getChunkSource().getChunkNow(ChunkPos.getX(ch), ChunkPos.getZ(ch));
            capturing.remove(ch);
            if (c == null) {
                want(ch, true);
                continue;
            }
            snap.put(ch, c, minY);
            stats.captured++;
        }
        while (!done && !ahead.isEmpty() && System.nanoTime() < until) {
            long ch = ahead.dequeueLong();
            if (snap.chunks.containsKey(ch)) continue;
            LevelChunk c = level.getChunkSource().getChunkNow(ChunkPos.getX(ch), ChunkPos.getZ(ch));
            if (c == null) continue;
            snap.put(ch, c, minY);
            stats.captured++;
        }
        if (stats.captured > n0) stats.captureNanos += System.nanoTime() - t0;
        for (Iterator<Waiter> it = waiting.iterator(); it.hasNext(); ) {
            Waiter w = it.next();
            boolean all = true;
            for (long ch : w.chunks) if (!snap.chunks.containsKey(ch) && !w.absent.contains(ch)) all = false;
            if (all) {
                it.remove();
                w.done.complete(w.absent.toLongArray());
            }
        }
        if (!done) return false;
        if (survey != null && !revalidated && revalidate()) return false;
        release();
        return true;
    }

    private void want(long ch, boolean urgent) {
        if (snap.chunks.containsKey(ch) || capturing.contains(ch) || pending.containsKey(ch)) return;
        if (loaded(ch)) {
            capturing.add(ch);
            toCapture.enqueue(ch);
            return;
        }
        if (urgent) toTicket.addAndMoveToFirst(ch);
        else toTicket.add(ch);
    }

    /**
     * The ground under the chosen route may have changed since it was captured (the player dug or built): capture its
     * chunks again and, if any of its columns changed, search once more.
     */
    private boolean revalidate() {
        revalidated = true;
        LongOpenHashSet chunks = new LongOpenHashSet();
        for (HousePaths.Cell c : survey.cells()) chunks.add(ChunkPos.pack(c.x() >> 4, c.z() >> 4));
        boolean changed = false;
        for (long ch : chunks) {
            LevelChunk c = level.getChunkSource().getChunkNow(ChunkPos.getX(ch), ChunkPos.getZ(ch));
            ColumnSnapshot.Chunk old = snap.chunks.get(ch);
            if (c == null || old == null || old == ColumnSnapshot.Chunk.MISSING) continue;
            ColumnSnapshot.Chunk now = ColumnSnapshot.capture(c, minY);
            if (!Arrays.equals(now.ground(), old.ground()) || !Arrays.equals(now.road(), old.road())) {
                snap.chunks.put(ch, now);
                snap.live.put(ch, c);
                view.forget(ChunkPos.getX(ch), ChunkPos.getZ(ch));
                changed = true;
            }
        }
        for (RouteSolver.Bore b : finalBores) {
            boolean ready = true;
            int rx = -RouteSolver.DZ[b.d()], rz = RouteSolver.DX[b.d()];
            for (int m = 1; m < b.len() && ready; m++)
                for (int l = -2; l <= 2; l += 4)
                    if (!level.hasChunkAt(new BlockPos(b.x() + RouteSolver.DX[b.d()] * m + l * rx, b.y(), b.z() + RouteSolver.DZ[b.d()] * m + l * rz))) ready = false;
            if (ready && !Tunnels.safe(level, b.x(), b.z(), b.d(), b.y(), b.len())) {
                synchronized (unsafe) {
                    unsafe.add(b.id());
                }
                changed = true;
            }
        }
        if (!changed) return false;
        stats.candLines.add("CR-STAKE-RESURVEY ground changed under the route");
        survey = null;
        failure = null;
        done = false;
        Thread.ofVirtual().name("CountryRoads-survey-" + IDS.incrementAndGet()).start(this);
        return true;
    }

    int requestedCount() {
        return stats.chunksReq;
    }

    // ---------------------------------------------------------------- worker

    @Override
    public boolean cancelled() {
        return cancelled;
    }

    @Override
    public void prefetch(long[] chunks, boolean load) {
        if (chunks.length == 0) return;
        if (load) {
            loads.addAndGet(chunks.length);
            wanted.add(chunks);
        } else looking.add(chunks);
    }

    @Override
    public long[] need(long[] chunks, boolean load) throws InterruptedException {
        if (load) loads.addAndGet(chunks.length);
        CompletableFuture<long[]> f = new CompletableFuture<>();
        waiters.add(new Waiter(chunks, load, new LongOpenHashSet(), f));
        long t0 = System.nanoTime();
        long[] absent = await(f, Long.MAX_VALUE);
        stats.waits++;
        stats.waitNanos += System.nanoTime() - t0;
        return absent == null ? new long[0] : absent;
    }

    /** Waits for the server thread (or a planner) without holding a compute turn; null when the deadline passed first. */
    private <T> T await(CompletableFuture<T> f, long deadline) throws InterruptedException {
        COMPUTE.release();
        holding = false;
        try {
            while (System.nanoTime() < deadline) {
                if (cancelled) throw new CancellationException();
                try {
                    return f.get(50, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                } catch (java.util.concurrent.ExecutionException e) {
                    return null;
                }
            }
            return null;
        } finally {
            COMPUTE.acquire();
            holding = true;
        }
    }

    @Override
    public void run() {
        try {
            COMPUTE.acquire();
            holding = true;
            compute();
        } catch (CancellationException | InterruptedException ignored) {
        } catch (Throwable t) {
            CountryRoads.LOG.error("House path survey from {} failed", stake, t);
            survey = null;
            failure = "countryroads.stake.no_road";
            failureArg = RADIUS;
        } finally {
            if (holding) {
                holding = false;
                COMPUTE.release();
            }
            done = true;
        }
    }

    private void compute() throws InterruptedException {
        long t0 = System.nanoTime();
        LongOpenHashSet goals = new LongOpenHashSet(loadedGoals);
        goals.addAll(LongArrayList.wrap(pathGoals));
        goals.addAll(planGoals(goals.isEmpty()));
        goals.remove(doorKey);
        long tGoals = System.nanoTime();
        if (goals.isEmpty()) {
            fail("countryroads.stake.no_road", RADIUS);
            return;
        }
        RouteSolver s = new RouteSolver(view, this, sx, sz);
        int[] all = s.distanceField(goals);
        Clusters clusters = new Clusters(goals);
        long[] toRoad = line(nearest(goals));
        int unloaded = 0;
        for (long ch : toRoad) if (!loadedAtStart.contains(ch)) unloaded++;
        CoarsePlanner coarse = null;
        RoadNetwork net = unloaded > COARSE_ABOVE ? RoadNetwork.get(level) : null;
        if (net != null) {
            // far from loaded land: plan the corridor on the generator's terrain first, fetch only that, in one batch
            long tc = System.nanoTime();
            coarse = new CoarsePlanner(view, net.terrain, sx, sz, RADIUS);
            LongOpenHashSet corridor = corridor(coarse, goals, CORRIDOR);
            if (corridor != null) {
                s.allowed = corridor;
                LongOpenHashSet fetch = new LongOpenHashSet(corridor);
                fetch.removeAll(loadedAtStart);
                prefetch(nearFirst(fetch), true);
            }
            stats.candLines.add(String.format(java.util.Locale.ROOT, "CR-STAKE-COARSE chunks=%d samples=%d ms=%d unloadedOnLine=%d",
                corridor == null ? -1 : corridor.size(), coarse.samples, (System.nanoTime() - tc) / 1_000_000, unloaded));
        } else prefetch(toRoad, true);

        // ---- the direct route: the player's waypoints in order, then candidates for the last leg
        boolean[] used = s.newUsed();
        used[s.doorIdx] = true;
        List<long[]> acc = new ArrayList<>();
        long start = doorKey;
        int inDir = RouteSolver.START, inLevel = 0;
        LongOpenHashSet forbidden;
        synchronized (unsafe) {
            forbidden = new LongOpenHashSet(unsafe);
        }
        List<RouteSolver.Bore> directBores = new ArrayList<>();
        // each leg sees the legs before it as the rest of its own chain (a switchback must not fold onto them)
        s.prefix(acc);
        for (int i = 0; i < waypoints.size(); i++) {
            RouteSolver.Route r = solve(s, start, inDir, inLevel, used, RouteSolver.Goal.column(waypoints.get(i)), true, forbidden, null);
            if (r == null) {
                fail("countryroads.stake.no_waypoint", i + 1);
                return;
            }
            acc.addAll(r.part());
            directBores.addAll(r.bores());
            for (long[] e : r.part()) used[s.idx(ChunkPos.getX(e[0]), ChunkPos.getZ(e[0]))] = true;
            inLevel = r.part().size() >= 2 ? level(view, r.part().get(r.part().size() - 2)[0]) : level(view, start);
            inDir = r.endDir();
            start = r.endKey();
        }
        boolean[] usedBefore = used.clone();
        List<Candidate> cands = new ArrayList<>();
        RouteSolver.Route best = solve(s, start, inDir, inLevel, used, RouteSolver.Goal.roads(all, -1), true, forbidden, null);
        if (best == null && s.allowed != null) {
            // the corridor was too narrow (a cliff or lake the coarse terrain did not show): wider, then everywhere
            LongOpenHashSet wide = corridor(coarse, goals, CORRIDOR_WIDE);
            s.allowed = wide;
            if (wide != null) best = solve(s, start, inDir, inLevel, used, RouteSolver.Goal.roads(all, -1), true, forbidden, null);
            if (best == null) {
                s.allowed = null;
                best = solve(s, start, inDir, inLevel, used, RouteSolver.Goal.roads(all, -1), true, forbidden, null);
            }
            stats.candLines.add("CR-STAKE-COARSE widened " + (wide == null ? "-" : wide.size()) + (s.allowed == null ? " then unrestricted" : ""));
        }
        if (best == null) {
            fail("countryroads.stake.no_road", RADIUS);
            return;
        }
        cands.add(new Candidate("nearest", best));
        // other roads close enough to win
        int bestCluster = clusters.near(best.endKey());
        int startIdx = s.idx(ChunkPos.getX(start), ChunkPos.getZ(start));
        List<int[]> others = new ArrayList<>();
        for (int c = 0; c < clusters.count; c++) {
            if (c == bestCluster) continue;
            int[] f = s.distanceField(clusters.members(c));
            // a route costs about EST per block of distance: roads that clearly cannot win are not searched
            if (f[startIdx] == Integer.MAX_VALUE || (long) f[startIdx] * EST > best.cost() * 13 / 10) continue;
            others.add(new int[]{f[startIdx], c});
            clusters.fields[c] = f;
        }
        others.sort((a, b) -> Integer.compare(a[0], b[0]));
        for (int k = 0; k < others.size() && cands.size() < MAX_CANDIDATES - 1; k++) {
            RouteSolver.Route r = solve(s, start, inDir, inLevel, used, RouteSolver.Goal.roads(clusters.fields[others.get(k)[1]], 4), true, forbidden, null, false);
            if (r != null && !known(cands, r)) cands.add(new Candidate("road" + (k + 2), r));
        }
        // a route kept apart from the first
        int[] penalty = new int[s.w * s.w];
        for (long[] e : best.part()) {
            int x = ChunkPos.getX(e[0]), z = ChunkPos.getZ(e[0]);
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2 + Math.abs(dx); dz <= 2 - Math.abs(dx); dz++)
                    if (view.inside(x + dx, z + dz)) penalty[s.idx(x + dx, z + dz)] = PENALTY;
        }
        RouteSolver.Route apart = solve(s, start, inDir, inLevel, used, RouteSolver.Goal.roads(all, -1), true, forbidden, penalty, false);
        if (apart != null) {
            int shared = 0;
            for (long[] e : apart.part()) if (penalty[s.idx(ChunkPos.getX(e[0]), ChunkPos.getZ(e[0]))] > 0) shared++;
            if (shared < apart.part().size() * 6 / 10 && !known(cands, apart)) cands.add(new Candidate("apart", apart));
        }
        // over the hill instead of through it
        if (!best.bores().isEmpty()) {
            RouteSolver.Route over = solve(s, start, inDir, inLevel, used, RouteSolver.Goal.roads(all, -1), false, forbidden, null, false);
            if (over != null && !known(cands, over)) cands.add(new Candidate("over", over));
        }
        Candidate win = null;
        for (Candidate c : cands) {
            List<long[]> chain = new ArrayList<>(acc);
            chain.addAll(c.route.part());
            // the plan reads the worn band beside the line (and the wandering pass its bends): those columns first
            s.ensureAround(chain, 7, c == cands.get(0));
            c.cost = s.routeCost(chain, c.route.endKey());
            HousePaths.Survey sv = PathPlan.build(view, sx, sz, stakeY, chain, c.route.endKey());
            if (sv == null) {
                if (c == cands.get(0)) {
                    fail("countryroads.stake.on_road", 0);
                    return;
                }
                continue;
            }
            c.score = c.cost + score(sv);
            int[] st = stairs(sv);
            stats.candLines.add(String.format(java.util.Locale.ROOT, "CR-STAKE-CAND %s cost=%d score=%d length=%d tunnel=%d stairs=%d run=%d bill=%d/%d/%d fetched=%d",
                c.kind, c.cost, c.score, sv.length(), sv.tunnelCells(), st[0], st[1], sv.earth(), sv.planks(), sv.torches(), c.loaded));
            if (win == null || c.score < win.score) win = c;
        }
        stats.candidates = cands.size();
        stats.candLines.add("CR-STAKE-WINNER " + win.kind);
        long tCands = System.nanoTime();
        List<long[]> direct = new ArrayList<>(acc);
        direct.addAll(win.route.part());
        directBores.addAll(win.route.bores());
        List<RouteSolver.Bore> bores = directBores;
        stats.costDirect = win.cost;

        // ---- the wandering pass: bends off the straight stretches, the player's waypoints between them
        List<long[]> chain = direct;
        long end = win.route.endKey();
        List<Long> vias = new ArrayList<>(s.meander(direct, waypoints, usedBefore));
        s.routeCost(direct, win.route.endKey());
        int directFolds = s.lastFolds;
        for (int attempt = 0; attempt < 6 && !vias.isEmpty(); attempt++) {
            boolean[] used2 = s.newUsed();
            used2[s.doorIdx] = true;
            List<long[]> acc2 = new ArrayList<>();
            List<RouteSolver.Bore> bores2 = new ArrayList<>();
            long st = doorKey;
            int dir = RouteSolver.START, lvl = inLevel;
            boolean ok = true;
            s.prefix(acc2);
            for (long v : vias) {
                RouteSolver.Route r = solve(s, st, dir, lvl, used2, RouteSolver.Goal.column(v), true, forbidden, null);
                if (r == null) {
                    ok = false;
                    break;
                }
                acc2.addAll(r.part());
                bores2.addAll(r.bores());
                for (long[] e : r.part()) used2[s.idx(ChunkPos.getX(e[0]), ChunkPos.getZ(e[0]))] = true;
                lvl = r.part().size() >= 2 ? level(view, r.part().get(r.part().size() - 2)[0]) : level(view, st);
                dir = r.endDir();
                st = r.endKey();
            }
            RouteSolver.Route last = ok ? solve(s, st, dir, lvl, used2, RouteSolver.Goal.roads(all, -1), true, forbidden, null) : null;
            s.prefix(null);
            if (last == null) break;
            acc2.addAll(last.part());
            bores2.addAll(last.bores());
            // a bend point can force a leg to fold back beside the one before (a hairpin with its legs side by side,
            // sharing the bed): that bend goes (the nearest to the first fold; the player's waypoints stay) and the
            // pass runs again
            s.routeCost(acc2, last.endKey());
            if (s.lastFolds <= directFolds) {
                chain = acc2;
                end = last.endKey();
                bores = bores2;
                break;
            }
            long fold = s.firstFold, drop = Long.MIN_VALUE;
            int bestD = Integer.MAX_VALUE;
            for (long v : vias) {
                if (waypoints.contains(v)) continue;
                int d = Math.max(Math.abs(ChunkPos.getX(v) - ChunkPos.getX(fold)), Math.abs(ChunkPos.getZ(v) - ChunkPos.getZ(fold)));
                if (d < bestD) {
                    bestD = d;
                    drop = v;
                }
            }
            stats.candLines.add("CR-STAKE-FOLDS the wandering route folds onto itself at " + s.lastFolds + " cells (direct " + directFolds + ")"
                + (drop == Long.MIN_VALUE ? ": kept the direct route" : ": bend " + ChunkPos.getX(drop) + "," + ChunkPos.getZ(drop) + " dropped"));
            if (drop == Long.MIN_VALUE) break;
            vias.remove(drop);
        }
        if (chain != direct && crossing(chain, view) != Long.MIN_VALUE) {
            // the wandering version passes over its own tunnel too low: the direct route does not need the bends
            chain = direct;
            end = win.route.endKey();
            bores = directBores;
            stats.candLines.add("CR-STAKE-CROSSING the wandering route crossed its tunnel: kept the direct route");
        }
        long crossed = crossing(chain, view);
        if (crossed != Long.MIN_VALUE && retries < 4) {
            // the route passes over its own tunnel too low (the path would sit in its headroom): no such bore, again
            synchronized (unsafe) {
                unsafe.add(crossed);
            }
            retries++;
            stats.candLines.add("CR-STAKE-CROSSING the route crossed its own tunnel: searched again without that bore");
            compute();
            return;
        }
        stats.costFinal = s.routeCost(chain, end);
        stats.candLines.add(String.format(java.util.Locale.ROOT, "CR-STAKE-PHASES goals=%dms (loaded %d, paths %d, all %d) candidates=%dms wander=%dms",
            (tGoals - t0) / 1_000_000, loadedGoals.length, pathGoals.length, goals.size(), (tCands - tGoals) / 1_000_000, (System.nanoTime() - tCands) / 1_000_000));
        HousePaths.Survey sv = PathPlan.build(view, sx, sz, stakeY, chain, end);
        if (sv == null) {
            fail("countryroads.stake.on_road", 0);
            return;
        }
        finalBores = bores;
        survey = sv;
    }

    /** A candidate route equal to one already in the list (another road's goal or the apart search can land on the same line). */
    private static boolean known(List<Candidate> cands, RouteSolver.Route r) {
        for (Candidate c : cands) {
            List<long[]> a = c.route.part(), b = r.part();
            if (c.route.endKey() != r.endKey() || a.size() != b.size()) continue;
            boolean same = true;
            for (int i = 0; i < a.size() && same; i++) same = a.get(i)[0] == b.get(i)[0];
            if (same) return true;
        }
        return false;
    }

    /**
     * The bore (Bore.id) whose tunnel another part of the route crosses too low (a cell on one of the tunnel's three
     * columns across, its surface not at least a block above the tunnel's roof), or Long.MIN_VALUE.
     */
    private static long crossing(List<long[]> chain, Terrain t) {
        it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap boreAt = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap floor = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        LongOpenHashSet tunnel = new LongOpenHashSet();
        for (int i = 0; i < chain.size(); i++) {
            long[] e = chain.get(i);
            if (e[1] != 1 || i == 0) continue;
            long entry = chain.get(i - 1)[0];
            int ex = ChunkPos.getX(entry), ez = ChunkPos.getZ(entry), len = (int) e[2];
            int d = PathPlan.dirOf(Integer.signum(ChunkPos.getX(e[0]) - ex), Integer.signum(ChunkPos.getZ(e[0]) - ez));
            int y = Terrain.level(t.ground(ex, ez));
            for (int m = 1; m < len && i + m - 1 < chain.size(); m++) {
                tunnel.add(chain.get(i + m - 1)[0]);
                // the tunnel's own columns: the bored line and one either side (it is three wide)
                int cx = ex + RouteSolver.DX[d] * m, cz = ez + RouteSolver.DZ[d] * m, rx = -RouteSolver.DZ[d], rz = RouteSolver.DX[d];
                for (int l = -1; l <= 1; l++) {
                    long k = ChunkPos.pack(cx + l * rx, cz + l * rz);
                    boreAt.put(k, RouteSolver.key(ex, ez) * 4 + d);
                    floor.put(k, y);
                }
            }
        }
        if (boreAt.isEmpty()) return Long.MIN_VALUE;
        for (long[] e : chain) {
            if (e[1] > 0 || tunnel.contains(e[0]) || !boreAt.containsKey(e[0])) continue;
            int lvl = Terrain.level(t.ground(ChunkPos.getX(e[0]), ChunkPos.getZ(e[0])));
            if (lvl <= floor.get(e[0]) + Tunnels.ROOF) return boreAt.get(e[0]);
        }
        return Long.MIN_VALUE;
    }

    private final class Candidate {
        final String kind;
        final RouteSolver.Route route;
        final int loaded;
        long cost, score;

        Candidate(String kind, RouteSolver.Route route) {
            this.kind = kind;
            this.route = route;
            this.loaded = loads.getAndSet(0);
        }
    }

    /** What the plan adds to the route cost when candidates are compared: stairs, the longest flight, the bill. */
    private static long score(HousePaths.Survey sv) {
        int[] st = stairs(sv);
        return 6L * st[0] + 15L * st[1] + sv.earth() + sv.stone() + 2L * sv.planks() + 8L * sv.torches();
    }

    /** {stair cells, longest run of stair cells} along the walked line. */
    private static int[] stairs(HousePaths.Survey sv) {
        int n = 0, run = 0, longest = 0;
        for (HousePaths.Cell c : sv.cells()) {
            if (c.edge()) continue;
            if (c.stair() >= 0) {
                n++;
                longest = Math.max(longest, ++run);
            } else run = 0;
        }
        return new int[]{n, longest};
    }

    private RouteSolver.Route solve(RouteSolver s, long start, int inDir, int inLevel, boolean[] used, RouteSolver.Goal goal, boolean bores,
                                    LongOpenHashSet forbidden, int[] penalty) throws InterruptedException {
        return solve(s, start, inDir, inLevel, used, goal, bores, forbidden, penalty, true);
    }

    private RouteSolver.Route solve(RouteSolver s, long start, int inDir, int inLevel, boolean[] used, RouteSolver.Goal goal, boolean bores,
                                    LongOpenHashSet forbidden, int[] penalty, boolean load) throws InterruptedException {
        RouteSolver.Route r = s.solve(start, inDir, inLevel, used, goal, bores, forbidden, penalty, load);
        stats.expansions += s.expansions;
        return r;
    }

    private void fail(String key, int arg) {
        survey = null;
        failure = key;
        failureArg = arg;
        if (player == null) CountryRoads.LOG.info("CR-STAKE-DEBUG expansions=" + stats.expansions);
    }

    private static int level(Terrain t, long k) {
        return Terrain.level(t.ground(ChunkPos.getX(k), ChunkPos.getZ(k)));
    }

    /**
     * Planned road centrelines in the window (open-sky parts, outside the villages at either end). Waits briefly for
     * roads not planned yet, longer when nothing else is known (a fresh area: without them there is no goal at all).
     */
    private LongOpenHashSet planGoals(boolean nothingElse) throws InterruptedException {
        LongOpenHashSet out = new LongOpenHashSet();
        RoadNetwork n = RoadNetwork.get(level);
        if (n == null) return out;
        List<Edge> edges = n.edgesNear(sx - RADIUS, sz - RADIUS, sx + RADIUS, sz + RADIUS, 32);
        List<CompletableFuture<EdgePlan>> plans = new ArrayList<>();
        for (Edge e : edges) plans.add(n.plan(e));
        CompletableFuture<Void> all = CompletableFuture.allOf(plans.toArray(CompletableFuture[]::new));
        if (!all.isDone()) await(all, System.nanoTime() + (nothingElse ? PLAN_WAIT_LONG_MS : PLAN_WAIT_MS) * 1_000_000L);
        for (CompletableFuture<EdgePlan> f : plans) {
            EdgePlan p = f.isDone() && !f.isCompletedExceptionally() ? f.join() : null;
            if (p == null) continue;
            BlockPos a = p.edge.a().pos(), b = p.edge.b().pos();
            for (int i = 0; i < p.length(); i++) {
                if (p.kind[i] == EdgePlan.TUNNEL) continue;
                int x = (int) Math.floor(p.x[i]), z = (int) Math.floor(p.z[i]);
                if (Math.max(Math.abs(x - sx), Math.abs(z - sz)) > RADIUS) continue;
                if (Math.hypot(x - a.getX(), z - a.getZ()) < 32 || Math.hypot(x - b.getX(), z - b.getZ()) < 32) continue;
                out.add(RouteSolver.key(x, z));
            }
        }
        return out;
    }

    /** The corridor of every leg (door, waypoints in order, the road), with the chunks loaded anyway. */
    private LongOpenHashSet corridor(CoarsePlanner coarse, LongOpenHashSet goals, int half) {
        LongOpenHashSet out = new LongOpenHashSet(loadedAtStart);
        int fx = sx, fz = sz;
        for (long w : waypoints) {
            LongOpenHashSet leg = coarse.corridor(fx, fz, List.of(w), half, COARSE_ROUTES);
            if (leg == null) return null;
            out.addAll(leg);
            fx = ChunkPos.getX(w);
            fz = ChunkPos.getZ(w);
        }
        LongOpenHashSet last = coarse.corridor(fx, fz, goals, half, COARSE_ROUTES);
        if (last == null) return null;
        out.addAll(last);
        return out;
    }

    /** Chunks ordered from the door outward (fetched in that order). */
    private long[] nearFirst(LongOpenHashSet chunks) {
        long[] a = chunks.toLongArray();
        long[] k = new long[a.length];
        for (int i = 0; i < a.length; i++) {
            int d = Math.abs(ChunkPos.getX(a[i]) - (sx >> 4)) + Math.abs(ChunkPos.getZ(a[i]) - (sz >> 4));
            k[i] = ((long) d << 32) | i;
        }
        Arrays.sort(k);
        long[] out = new long[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[(int) k[i]];
        return out;
    }

    private long nearest(LongOpenHashSet goals) {
        long best = 0;
        int bd = Integer.MAX_VALUE;
        for (long g : goals) {
            int d = Math.abs(ChunkPos.getX(g) - sx) + Math.abs(ChunkPos.getZ(g) - sz);
            if (d < bd) {
                bd = d;
                best = g;
            }
        }
        return best;
    }

    /** The chunks along the straight line from the door to a column, one chunk either side. */
    private long[] line(long to) {
        LongLinkedOpenHashSet out = new LongLinkedOpenHashSet();
        int tx = ChunkPos.getX(to), tz = ChunkPos.getZ(to);
        int steps = Math.max(1, Math.max(Math.abs(tx - sx), Math.abs(tz - sz)) / 8);
        for (int i = 0; i <= steps; i++) {
            int x = sx + (tx - sx) * i / steps, z = sz + (tz - sz) * i / steps;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    int cx = (x >> 4) + dx, cz = (z >> 4) + dz;
                    if (snap.get(cx, cz) == null) out.add(ChunkPos.pack(cx, cz));
                }
        }
        return out.toLongArray();
    }

    /** Goal columns grouped into roads: columns whose chunks lie within 3 chunks of each other share a group. */
    private static final class Clusters {
        final int count;
        final int[][] fields;
        private final Long2IntOpenHashMap of = new Long2IntOpenHashMap();
        private final List<List<Long>> members = new ArrayList<>();

        Clusters(LongOpenHashSet goals) {
            Long2IntOpenHashMap bin = new Long2IntOpenHashMap();
            bin.defaultReturnValue(-1);
            List<Long> bins = new ArrayList<>();
            for (long g : goals) {
                long b = ChunkPos.pack(ChunkPos.getX(g) >> 4, ChunkPos.getZ(g) >> 4);
                if (bin.putIfAbsent(b, bins.size()) == -1) bins.add(b);
            }
            int[] parent = new int[bins.size()];
            for (int i = 0; i < parent.length; i++) parent[i] = i;
            for (int i = 0; i < bins.size(); i++) {
                int bx = ChunkPos.getX(bins.get(i)), bz = ChunkPos.getZ(bins.get(i));
                for (int dx = -3; dx <= 3; dx++)
                    for (int dz = -3; dz <= 3; dz++) {
                        int j = bin.get(ChunkPos.pack(bx + dx, bz + dz));
                        if (j >= 0) parent[find(parent, i)] = find(parent, j);
                    }
            }
            Long2IntOpenHashMap id = new Long2IntOpenHashMap();
            id.defaultReturnValue(-1);
            of.defaultReturnValue(-1);
            for (long g : goals) {
                int root = find(parent, bin.get(ChunkPos.pack(ChunkPos.getX(g) >> 4, ChunkPos.getZ(g) >> 4)));
                int c = id.get(root);
                if (c < 0) {
                    c = members.size();
                    id.put(root, c);
                    members.add(new ArrayList<>());
                }
                members.get(c).add(g);
                of.put(g, c);
            }
            count = members.size();
            fields = new int[count][];
        }

        private static int find(int[] p, int i) {
            while (p[i] != i) i = p[i] = p[p[i]];
            return i;
        }

        List<Long> members(int c) {
            return members.get(c);
        }

        /** The group of the goal nearest a road column the route ended on (the painted road is a little wider than its line). */
        int near(long end) {
            int x = ChunkPos.getX(end), z = ChunkPos.getZ(end);
            for (int r = 0; r <= 6; r++)
                for (int dx = -r; dx <= r; dx++) {
                    int dz = r - Math.abs(dx);
                    int c = of.get(ChunkPos.pack(x + dx, z + dz));
                    if (c >= 0) return c;
                    c = of.get(ChunkPos.pack(x + dx, z - dz));
                    if (c >= 0) return c;
                }
            return -1;
        }
    }
}
