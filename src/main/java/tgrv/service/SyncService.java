package tgrv.service;

import tgrv.model.LogServer;
import tgrv.model.CondensedRound;
import tgrv.net.LogHttpClient;
import tgrv.store.RoundCache;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SyncService {

    private static final Logger LOG = Logger.getLogger(SyncService.class.getName());

    public static final int RETENTION_DAYS = 30;
    public static final long POLL_INTERVAL_MINUTES = 30;
    private static final int FETCH_CONCURRENCY = 12;

    private final RoundCache cache;
    private final LogHttpClient client;
    private final List<LogServer> servers;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tgrv-sync");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService fetchPool = Executors.newFixedThreadPool(FETCH_CONCURRENCY, r -> {
        Thread t = new Thread(r, "tgrv-fetch");
        t.setDaemon(true);
        return t;
    });

    private volatile Consumer<SyncEvent> listener = e -> {
    };

    public SyncService(RoundCache cache, LogHttpClient client, List<LogServer> servers) {
        this.cache = cache;
        this.client = client;
        this.servers = servers;
    }

    public void setListener(Consumer<SyncEvent> listener) {
        this.listener = listener == null ? e -> {
        } : listener;
    }

    public void start(long initialDelaySeconds) {
        cache.load();
        scheduler.scheduleWithFixedDelay(this::runSafely, initialDelaySeconds, POLL_INTERVAL_MINUTES * 60, TimeUnit.SECONDS);
    }

    public void triggerNow() {
        scheduler.execute(this::runSafely);
    }

    public void shutdown() {
        scheduler.shutdownNow();
        fetchPool.shutdownNow();
    }

    private void runSafely() {
        listener.accept(SyncEvent.started());
        try {
            SyncEvent.Result result = runOnce();
            listener.accept(SyncEvent.finished(result, null));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Sync cycle failed", e);
            listener.accept(SyncEvent.finished(new SyncEvent.Result(0, 0, cache.all().size()), e));
        }
    }

    private SyncEvent.Result runOnce() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate cutoff = today.minusDays(RETENTION_DAYS - 1L);

        cache.pruneOlderThan(cutoff);

        int daysListed = 0;
        int fetched = 0;
        for (LogServer server : servers) {
            SyncEvent.Result r = syncServer(server, today, cutoff);
            daysListed += r.daysChecked;
            fetched += r.roundsFetched;
        }

        return new SyncEvent.Result(daysListed, fetched, cache.all().size());
    }

    private SyncEvent.Result syncServer(LogServer server, LocalDate today, LocalDate cutoff) {
        List<LocalDate> daysToCheck = new ArrayList<>();
        daysToCheck.add(today);
        for (LocalDate d = cutoff; !d.isAfter(today); d = d.plusDays(1)) {
            if (!cache.isDayListed(server.name, d) && !daysToCheck.contains(d)) {
                daysToCheck.add(d);
            }
        }

        Map<LocalDate, List<Integer>> dayRounds = new ConcurrentHashMap<>();
        AtomicInteger globalMaxId = new AtomicInteger(cache.all().stream()
                .filter(rc -> rc.serverName.equals(server.name))
                .mapToInt(rc -> rc.roundId).max().orElse(-1));
        AtomicInteger daysListed = new AtomicInteger(0);

        runConcurrently(daysToCheck, day -> listDay(server, day, dayRounds, globalMaxId, daysListed));

        LocalDate yesterday = today.minusDays(1);
        boolean yesterdayNeedsRecheck = dayRounds.getOrDefault(today, List.of()).isEmpty()
                || cache.all().stream().anyMatch(rc -> rc.serverName.equals(server.name) && rc.date.equals(yesterday) && !rc.isFinal);
        if (!yesterday.isBefore(cutoff) && yesterdayNeedsRecheck && !dayRounds.containsKey(yesterday)) {
            listDay(server, yesterday, dayRounds, globalMaxId, daysListed);
        }

        List<Runnable> fetchTasks = new ArrayList<>();
        AtomicInteger fetched = new AtomicInteger(0);
        for (Map.Entry<LocalDate, List<Integer>> entry : dayRounds.entrySet()) {
            LocalDate day = entry.getKey();
            for (int id : entry.getValue()) {
                fetchTasks.add(() -> fetchRound(server, day, id, globalMaxId.get(), fetched));
            }
        }
        runConcurrently(fetchTasks, Runnable::run);

        return new SyncEvent.Result(daysListed.get(), fetched.get(), 0);
    }

    private void listDay(LogServer server, LocalDate day, Map<LocalDate, List<Integer>> dayRounds,
                          AtomicInteger globalMaxId, AtomicInteger daysListed) {
        try {
            List<Integer> ids = client.listRoundsForDay(server, day);
            cache.markDayListed(server.name, day);
            dayRounds.put(day, ids);
            daysListed.incrementAndGet();
            for (int id : ids) {
                globalMaxId.accumulateAndGet(id, Math::max);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[" + server.name + "] Failed to list rounds for " + day, e);
        }
    }

    private void fetchRound(LogServer server, LocalDate day, int id, int globalMaxId, AtomicInteger fetched) {
        CondensedRound existing = cache.get(server.name, id);
        boolean wasFinal = existing != null && existing.isFinal;
        boolean isFinal = id != globalMaxId;

        if (!wasFinal) {
            try {
                CondensedRound rc = client.fetchCondensed(server, day, id, isFinal);
                cache.put(rc);
                fetched.incrementAndGet();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "[" + server.name + "] Failed to fetch round " + id + " (" + day + ")", e);
            }
        }

        if (!(wasFinal && cache.hasRawLog(server.name, day, id))) {
            try {
                String raw = client.fetchRawLog(server, day, id);
                if (raw != null) {
                    cache.writeRawLog(server.name, day, id, raw);
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "[" + server.name + "] Failed to fetch raw log for round " + id + " (" + day + ")", e);
            }
        }
    }

    private <T> void runConcurrently(List<T> items, Consumer<T> task) {
        List<Future<?>> futures = new ArrayList<>(items.size());
        for (T item : items) {
            futures.add(fetchPool.submit(() -> task.accept(item)));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Fetch task failed unexpectedly", e);
            }
        }
    }

    public static final class SyncEvent {
        public final boolean started;
        public final Instant time = Instant.now();
        public final Result result;
        public final Exception error;

        private SyncEvent(boolean started, Result result, Exception error) {
            this.started = started;
            this.result = result;
            this.error = error;
        }

        static SyncEvent started() {
            return new SyncEvent(true, null, null);
        }

        static SyncEvent finished(Result result, Exception error) {
            return new SyncEvent(false, result, error);
        }

        public static final class Result {
            public final int daysChecked;
            public final int roundsFetched;
            public final int roundsCached;

            public Result(int daysChecked, int roundsFetched, int roundsCached) {
                this.daysChecked = daysChecked;
                this.roundsFetched = roundsFetched;
                this.roundsCached = roundsCached;
            }
        }
    }
}
