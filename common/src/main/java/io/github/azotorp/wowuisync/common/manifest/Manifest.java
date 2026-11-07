package io.github.azotorp.wowuisync.common.manifest;

import io.github.azotorp.wowuisync.common.hash.Hashing;
import io.github.azotorp.wowuisync.common.path.SyncPaths;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Immutable list of synced files, sorted by case-insensitive path (DESIGN.md §11.1). */
public final class Manifest {
    public static final Comparator<ManifestEntry> ORDER =
            Comparator.comparing((ManifestEntry e) -> SyncPaths.key(e.path())).thenComparing(ManifestEntry::path);

    private static final Manifest EMPTY = new Manifest(List.of());

    private final List<ManifestEntry> entries;
    private final Map<String, ManifestEntry> byKey;
    private volatile String fingerprint;

    public Manifest(Collection<ManifestEntry> entries) {
        List<ManifestEntry> sorted = new ArrayList<>(entries);
        sorted.sort(ORDER);
        this.entries = Collections.unmodifiableList(sorted);
        Map<String, ManifestEntry> m = new LinkedHashMap<>();
        for (ManifestEntry e : sorted) m.put(SyncPaths.key(e.path()), e);
        this.byKey = Collections.unmodifiableMap(m);
    }

    public static Manifest empty() {
        return EMPTY;
    }

    public List<ManifestEntry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public ManifestEntry get(String path) {
        return byKey.get(SyncPaths.key(path));
    }

    public Map<String, ManifestEntry> byKey() {
        return byKey;
    }

    public long totalBytes() {
        long t = 0;
        for (ManifestEntry e : entries) t += e.size();
        return t;
    }

    public Manifest filter(Predicate<ManifestEntry> keep) {
        List<ManifestEntry> out = new ArrayList<>();
        for (ManifestEntry e : entries) if (keep.test(e)) out.add(e);
        return new Manifest(out);
    }

    /** SHA-256 over the sorted {@code path|size|sha256} lines, each terminated by '\n'. */
    public String fingerprint() {
        String f = fingerprint;
        if (f == null) {
            f = fingerprintOf(entries);
            fingerprint = f;
        }
        return f;
    }

    public static String fingerprintOf(Collection<ManifestEntry> entries) {
        List<ManifestEntry> sorted = new ArrayList<>(entries);
        sorted.sort(ORDER);
        MessageDigest md = Hashing.newSha256();
        for (ManifestEntry e : sorted) {
            md.update(e.fingerprintLine().getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\n');
        }
        return Hashing.hex(md.digest());
    }
}
