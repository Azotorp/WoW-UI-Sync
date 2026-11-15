package io.github.azotorp.wowuisync.common.addon;

import io.github.azotorp.wowuisync.common.manifest.Manifest;
import io.github.azotorp.wowuisync.common.manifest.ManifestEntry;
import io.github.azotorp.wowuisync.common.path.SyncPaths;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Inventories independent top-level folders and selects active-version TOC metadata. */
public final class AddonGrouper {
    private AddonGrouper() {}

    /** Top-level addon folders (original casing) present in a manifest. */
    public static List<String> folders(Manifest m) {
        Map<String, String> byLc = new TreeMap<>();
        for (ManifestEntry e : m.entries()) {
            String f = SyncPaths.addonFolder(e.path());
            if (f != null) byLc.putIfAbsent(f.toLowerCase(Locale.ROOT), f);
        }
        return new ArrayList<>(byLc.values());
    }

    /** Active Forever TOC; use the version-aware overload when the selected profile is known. */
    public static Map<String, ManifestEntry> tocEntries(Manifest m) { return tocEntries(m,"forever"); }

    /**
     * Groups folders. {@code tocs} maps lower-case folder name to its parsed toc (may be missing).
     */
    public static List<AddonGroup> group(Collection<String> folders, Map<String, TocInfo> tocs) {
        List<AddonGroup> out = new ArrayList<>();
        for (String folder : folders) {
            TocInfo t = tocs.get(lc(folder));
            out.add(new AddonGroup(folder, t == null ? folder : t.title(), List.of(folder)));
        }
        out.sort(Comparator.comparing(AddonGroup::displayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(AddonGroup::mainFolder, String.CASE_INSENSITIVE_ORDER).thenComparing(AddonGroup::mainFolder));
        return out;
    }

    /** Select the active version's TOC before the generic TOC. Unsupported version TOCs are not guessed. */
    public static Map<String, ManifestEntry> tocEntries(Manifest m, String profile) {
        Map<String, ManifestEntry> out = new HashMap<>();
        Map<String, Integer> ranks = new HashMap<>();
        for (ManifestEntry e : m.entries()) {
            String folder = SyncPaths.addonFolder(e.path());
            if (folder == null) continue;
            String file = e.path().substring(SyncPaths.ADDONS_PREFIX.length() + folder.length() + 1);
            if (file.contains("/") || !file.toLowerCase(Locale.ROOT).endsWith(".toc")) continue;
            String suffix = file.substring(0, file.length()-4);
            int rank = suffix.equalsIgnoreCase(folder + "_" + profile) ? 0
                    : suffix.equalsIgnoreCase(folder) ? 1 : 9;
            if (rank == 9) continue;
            String key = lc(folder);
            ManifestEntry previous = out.get(key);
            if (previous == null || rank < ranks.get(key) || rank == ranks.get(key) && e.path().compareTo(previous.path()) < 0) {
                out.put(key, e); ranks.put(key, rank);
            }
        }
        return out;
    }

    private static String lc(String s) { return s.toLowerCase(Locale.ROOT); }
}
