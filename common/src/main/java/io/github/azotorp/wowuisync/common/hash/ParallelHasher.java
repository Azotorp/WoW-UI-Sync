package io.github.azotorp.wowuisync.common.hash;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Hashes many files with one thread per core (max 8). */
public final class ParallelHasher {
    public interface Progress {
        void update(int done, int total);
    }

    public record Job(String key, Path file) {}

    private ParallelHasher() {}

    public static int defaultThreads() {
        return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
    }

    /**
     * Hashes every job's file. Returns key → sha256. Files that fail to read are reported in {@code errors}.
     */
    public static Map<String, String> hashAll(List<Job> jobs, Progress progress, Map<String, IOException> errors,
                                              BooleanSupplier cancelled) throws InterruptedException {
        Map<String, String> out = new ConcurrentHashMap<>();
        if (jobs.isEmpty()) return out;
        int total = jobs.size();
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(defaultThreads(), r -> {
            Thread t = new Thread(r, "hasher");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>(jobs.size());
            for (Job j : jobs) {
                futures.add(pool.submit(() -> {
                    if (cancelled != null && cancelled.getAsBoolean()) return;
                    try {
                        out.put(j.key(), Hashing.sha256Hex(j.file()));
                    } catch (IOException e) {
                        if (errors != null) errors.put(j.key(), e);
                    }
                    int d = done.incrementAndGet();
                    if (progress != null) progress.update(d, total);
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IllegalStateException(e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        if (cancelled != null && cancelled.getAsBoolean()) throw new InterruptedException("cancelled");
        return out;
    }
}
