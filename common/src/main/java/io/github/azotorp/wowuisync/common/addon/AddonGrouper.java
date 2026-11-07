package io.github.azotorp.wowuisync.common.addon;

import io.github.azotorp.wowuisync.common.manifest.Manifest;
import io.github.azotorp.wowuisync.common.manifest.ManifestEntry;
import io.github.azotorp.wowuisync.common.path.SyncPaths;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Groups addon folders into addons (DESIGN.md §15.1). */
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

    /**
     * The manifest entry of each folder's .toc: {@code <Folder>/<Folder>.toc} first, otherwise any .toc directly
     * inside the folder (flavor-suffixed tocs such as {@code Folder_Mainline.toc} included).
     */
    public static Map<String, ManifestEntry> tocEntries(Manifest m) {
        Map<String, ManifestEntry> exact = new HashMap<>();
        Map<String, ManifestEntry> any = new HashMap<>();
        for (ManifestEntry e : m.entries()) {
            String folder = SyncPaths.addonFolder(e.path());
            if (folder == null) continue;
            String rest = e.path().substring(SyncPaths.ADDONS_PREFIX.length() + folder.length() + 1);
            if (rest.contains("/") || !rest.toLowerCase(Locale.ROOT).endsWith(".toc")) continue;
            String lc = folder.toLowerCase(Locale.ROOT);
            if (rest.equalsIgnoreCase(folder + ".toc")) exact.put(lc, e);
            else any.merge(lc, e, (a, b) -> a.path().compareToIgnoreCase(b.path()) <= 0 ? a : b);
        }
        Map<String, ManifestEntry> out = new HashMap<>(any);
        out.putAll(exact);
        return out;
    }

    /**
     * Groups folders. {@code tocs} maps lower-case folder name to its parsed toc (may be missing).
     */
    public static List<AddonGroup> group(Collection<String> folders, Map<String, TocInfo> tocs) {
        List<String> list = new ArrayList<>(folders);
        list.sort(Comparator.comparing((String s) -> s.length()).thenComparing(String.CASE_INSENSITIVE_ORDER));
        Map<String, String> parent = new HashMap<>();
        for (String f : list) parent.put(lc(f), lc(f));

        for (String a : list) {
            for (String b : list) {
                if (a == b || a.equalsIgnoreCase(b)) continue;
                if (joins(b, a, tocs)) union(parent, lc(a), lc(b));
            }
        }

        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String f : list) groups.computeIfAbsent(find(parent, lc(f)), k -> new ArrayList<>()).add(f);
        List<AddonGroup> out = new ArrayList<>();
        for (List<String> g : groups.values()) {
            g.sort(Comparator.comparing((String s) -> s.length()).thenComparing(String.CASE_INSENSITIVE_ORDER));
            String main = g.get(0);
            TocInfo t = tocs.get(lc(main));
            String name = t != null && t.title() != null && !t.title().isBlank() ? t.title() : main;
            List<String> sorted = new ArrayList<>(g);
            sorted.sort(String.CASE_INSENSITIVE_ORDER);
            out.add(new AddonGroup(main, name, List.copyOf(sorted)));
        }
        out.sort(Comparator.comparing(AddonGroup::displayName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** True if folder {@code b} joins addon {@code a}. */
    static boolean joins(String b, String a, Map<String, TocInfo> tocs) {
        String bl = lc(b), al = lc(a);
        TocInfo tb = tocs.get(bl);
        TocInfo ta = tocs.get(al);
        if (bl.length() > al.length() + 1 && bl.startsWith(al)) {
            char sep = bl.charAt(al.length());
            if ((sep == '_' || sep == '-') && tb != null) {
                for (String d : tb.dependencies()) if (d.equalsIgnoreCase(a)) return true;
            }
        }
        if (ta != null && tb != null && (bl.startsWith(al) || al.startsWith(bl))) {
            if (sameId(ta.curseId(), tb.curseId()) || sameId(ta.wagoId(), tb.wagoId()) || sameId(ta.wowiId(), tb.wowiId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameId(String x, String y) {
        return x != null && Objects.equals(x, y);
    }

    private static String lc(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    private static String find(Map<String, String> p, String x) {
        String r = x;
        while (!p.get(r).equals(r)) r = p.get(r);
        String c = x;
        while (!p.get(c).equals(r)) {
            String n = p.get(c);
            p.put(c, r);
            c = n;
        }
        return r;
    }

    private static void union(Map<String, String> p, String a, String b) {
        String ra = find(p, a), rb = find(p, b);
        if (ra.equals(rb)) return;
        // Keep the shorter name as root so the main folder is stable.
        if (ra.length() <= rb.length()) p.put(rb, ra);
        else p.put(ra, rb);
    }
}
