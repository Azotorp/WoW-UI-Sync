package io.github.azotorp.wowuisync.client.sync;

import io.github.azotorp.wowuisync.client.data.ClientDb;
import io.github.azotorp.wowuisync.client.util.CancelledException;
import io.github.azotorp.wowuisync.client.util.ClientLog;
import io.github.azotorp.wowuisync.common.hash.HashCacheEntry;
import io.github.azotorp.wowuisync.common.hash.ParallelHasher;
import io.github.azotorp.wowuisync.common.manifest.Manifest;
import io.github.azotorp.wowuisync.common.manifest.ManifestEntry;
import io.github.azotorp.wowuisync.common.path.Exclusions;
import io.github.azotorp.wowuisync.common.path.SyncPaths;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Scans a flavor folder with the hash cache (DESIGN.md §11.1). */
public final class Scanner {
    /** Progress: "Scanning files: N of M". */
    public interface Progress {
        void update(int done, int total);
    }

    private record Found(String rel, Path abs, long size, long mtime) {}

    private Scanner() {}

    public static LocalScan scan(Path flavor, Exclusions exclusions, ClientDb db, Progress progress, BooleanSupplier cancelled)
            throws IOException, SQLException, CancelledException {
        List<Found> found = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        walk(flavor, flavor.resolve("WTF"), exclusions, found, invalid);
        Path addons = flavor.resolve("Interface").resolve("AddOns");
        if (Files.isDirectory(addons)) {
            try (var s = Files.list(addons)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) walk(flavor, p, exclusions, found, invalid);
                }
            }
        }
        String flavorKey = flavor.toAbsolutePath().normalize().toString();
        Map<String, HashCacheEntry> cache = db.hashCache(flavorKey);
        List<ParallelHasher.Job> jobs = new ArrayList<>();
        Map<String, String> known = new HashMap<>();
        for (Found f : found) {
            HashCacheEntry c = cache.get(f.rel());
            if (c != null && c.matches(f.size(), f.mtime())) known.put(f.rel(), c.sha256());
            else jobs.add(new ParallelHasher.Job(f.rel(), f.abs()));
        }
        Map<String, IOException> errors = new HashMap<>();
        int already = known.size();
        int total = found.size();
        if (progress != null) progress.update(already, total);
        Map<String, String> hashed;
        try {
            hashed = ParallelHasher.hashAll(jobs, (d, t) -> {
                if (progress != null) progress.update(already + d, total);
            }, errors, cancelled);
        } catch (InterruptedException e) {
            throw new CancelledException();
        }
        known.putAll(hashed);
        for (Map.Entry<String, IOException> e : errors.entrySet()) {
            ClientLog.LOG.warning("Could not read " + e.getKey() + ": " + e.getValue().getMessage());
        }
        if (!errors.isEmpty()) {
            String first = errors.keySet().iterator().next();
            throw new IOException("Could not read " + errors.size() + " files (for example " + first
                    + "). Close programs that may lock them and try again.");
        }
        List<ManifestEntry> entries = new ArrayList<>(found.size());
        List<HashCacheEntry> newCache = new ArrayList<>(found.size());
        Map<String, Path> files = new HashMap<>();
        for (Found f : found) {
            String sha = known.get(f.rel());
            if (sha == null) continue;
            entries.add(new ManifestEntry(f.rel(), f.size(), sha));
            newCache.add(new HashCacheEntry(f.rel(), f.size(), f.mtime(), sha));
            files.put(SyncPaths.key(f.rel()), f.abs());
        }
        db.replaceHashCache(flavorKey, newCache);
        List<String> paths = new ArrayList<>(entries.size());
        for (ManifestEntry e : entries) paths.add(e.path());
        return new LocalScan(flavor, new Manifest(entries), files, invalid, SyncPaths.caseCollisions(paths));
    }

    private static void walk(Path flavor, Path dir, Exclusions ex, List<Found> out, List<String> invalid) throws IOException {
        if (!Files.isDirectory(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                // Skip folders whose whole content is excluded (e.g. .git): probe with an unlikely nested name.
                String rel = rel(flavor, d);
                if (ex.isExcluded(rel + "/wus-probe-7f3a/x.wusprobe") && ex.isExcluded(rel + "/x.wusprobe")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                String rel = rel(flavor, f);
                if (!SyncPaths.isSyncedPath(rel) || ex.isExcluded(rel)) return FileVisitResult.CONTINUE;
                String bad = SyncPaths.invalidWindowsNameReason(rel);
                if (bad != null) {
                    invalid.add(rel + " (" + bad + ")");
                    return FileVisitResult.CONTINUE;
                }
                out.add(new Found(rel, f, attrs.size(), attrs.lastModifiedTime().toMillis()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path f, IOException e) {
                ClientLog.LOG.warning("Cannot read " + f + ": " + e.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static String rel(Path flavor, Path p) {
        return flavor.relativize(p).toString().replace('\\', '/');
    }
}
