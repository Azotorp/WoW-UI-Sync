package io.github.azotorp.wowuisync.common.manifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Difference between a current state and a target state. Applying it to {@code from} yields {@code to}.
 * Edited = same path (case-insensitive) with different content or casing.
 */
public record ManifestDiff(List<ManifestEntry> added, List<ManifestEntry> edited, List<ManifestEntry> removed) {

    public static ManifestDiff between(Manifest from, Manifest to) {
        List<ManifestEntry> added = new ArrayList<>();
        List<ManifestEntry> edited = new ArrayList<>();
        List<ManifestEntry> removed = new ArrayList<>();
        Map<String, ManifestEntry> fromMap = from.byKey();
        for (Map.Entry<String, ManifestEntry> e : to.byKey().entrySet()) {
            ManifestEntry old = fromMap.get(e.getKey());
            ManifestEntry now = e.getValue();
            if (old == null) added.add(now);
            else if (!old.sha256().equals(now.sha256()) || old.size() != now.size() || !old.path().equals(now.path())) edited.add(now);
        }
        Map<String, ManifestEntry> toMap = to.byKey();
        for (Map.Entry<String, ManifestEntry> e : fromMap.entrySet()) {
            if (!toMap.containsKey(e.getKey())) removed.add(e.getValue());
        }
        return new ManifestDiff(added, edited, removed);
    }

    public boolean isEmpty() {
        return added.isEmpty() && edited.isEmpty() && removed.isEmpty();
    }

    /** Bytes of new and edited content. */
    public long transferBytes() {
        long t = 0;
        for (ManifestEntry e : added) t += e.size();
        for (ManifestEntry e : edited) t += e.size();
        return t;
    }

    /** Applies this diff (as a delta) on top of {@code base}. */
    public Manifest applyTo(Manifest base) {
        Map<String, ManifestEntry> m = new java.util.LinkedHashMap<>(base.byKey());
        for (ManifestEntry e : removed) m.remove(io.github.azotorp.wowuisync.common.path.SyncPaths.key(e.path()));
        for (ManifestEntry e : added) m.put(io.github.azotorp.wowuisync.common.path.SyncPaths.key(e.path()), e);
        for (ManifestEntry e : edited) m.put(io.github.azotorp.wowuisync.common.path.SyncPaths.key(e.path()), e);
        return new Manifest(m.values());
    }
}
