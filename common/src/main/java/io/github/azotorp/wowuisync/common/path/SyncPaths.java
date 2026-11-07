package io.github.azotorp.wowuisync.common.path;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Path rules shared by client and server (DESIGN.md §14).
 * Paths are relative to the flavor folder, use forward slashes, keep their original casing and are compared
 * case-insensitively via {@link #key(String)}.
 */
public final class SyncPaths {
    public static final String ADDONS_PREFIX = "Interface/AddOns/";
    public static final String WTF_PREFIX = "WTF/";

    private static final String WINDOWS_INVALID = "<>:\"|?*";
    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private SyncPaths() {}

    /** Case-insensitive comparison key. */
    public static String key(String path) {
        return path.toLowerCase(Locale.ROOT);
    }

    /**
     * Normalizes separators to '/', strips "./" segments and duplicate slashes.
     * @throws IllegalArgumentException if the path is absolute, empty, or contains "..".
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) throw new IllegalArgumentException("Empty path");
        String p = raw.replace('\\', '/');
        if (p.startsWith("/") || (p.length() >= 2 && p.charAt(1) == ':')) {
            throw new IllegalArgumentException("Absolute path not allowed: " + raw);
        }
        StringBuilder sb = new StringBuilder();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) throw new IllegalArgumentException("'..' not allowed: " + raw);
            if (seg.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL in path: " + raw);
            if (!sb.isEmpty()) sb.append('/');
            sb.append(seg);
        }
        if (sb.isEmpty()) throw new IllegalArgumentException("Empty path: " + raw);
        return sb.toString();
    }

    /** True if {@code path} is already normalized, relative, and safe (used by the server to reject bad input). */
    public static boolean isSafeRelative(String path) {
        try {
            return normalize(path).equals(path);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Returns a reason if a segment is not a valid Windows file name, else null. */
    public static String invalidWindowsNameReason(String path) {
        for (String seg : path.split("/")) {
            for (int i = 0; i < seg.length(); i++) {
                char c = seg.charAt(i);
                if (c < 32) return "control character in \"" + seg + "\"";
                if (WINDOWS_INVALID.indexOf(c) >= 0) return "character '" + c + "' in \"" + seg + "\"";
            }
            if (seg.endsWith(".") || seg.endsWith(" ")) return "trailing dot or space in \"" + seg + "\"";
            String base = seg.contains(".") ? seg.substring(0, seg.indexOf('.')) : seg;
            if (WINDOWS_RESERVED.contains(base.toLowerCase(Locale.ROOT))) return "reserved name \"" + seg + "\"";
        }
        return null;
    }

    /** True if the path is inside one of the synced roots: {@code Interface/AddOns/<folder>/...} or {@code WTF/...}. */
    public static boolean isSyncedPath(String path) {
        String k = key(path);
        if (k.startsWith("wtf/") && k.length() > 4) return true;
        if (k.startsWith("interface/addons/")) {
            String rest = path.substring(ADDONS_PREFIX.length());
            int slash = rest.indexOf('/');
            // Loose files directly in Interface/AddOns are ignored everywhere.
            return slash > 0 && slash < rest.length() - 1;
        }
        return false;
    }

    /** Top-level addon folder name for a path under Interface/AddOns, or null. */
    public static String addonFolder(String path) {
        if (!key(path).startsWith("interface/addons/")) return null;
        String rest = path.substring(ADDONS_PREFIX.length());
        int slash = rest.indexOf('/');
        return slash > 0 ? rest.substring(0, slash) : null;
    }

    public static boolean isWtf(String path) {
        return key(path).startsWith("wtf/");
    }

    /** Groups of paths that differ only by case. Empty if none. */
    public static List<List<String>> caseCollisions(Collection<String> paths) {
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        for (String p : paths) byKey.computeIfAbsent(key(p), k -> new ArrayList<>()).add(p);
        List<List<String>> out = new ArrayList<>();
        for (List<String> group : byKey.values()) if (group.size() > 1) out.add(group);
        return out;
    }

    /** File extension in lower case including the dot (".lua"), or "" if none. */
    public static String extension(String path) {
        int slash = path.lastIndexOf('/');
        String name = path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return "";
        return name.substring(dot).toLowerCase(Locale.ROOT);
    }

    /** Last segment of the path. */
    public static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
