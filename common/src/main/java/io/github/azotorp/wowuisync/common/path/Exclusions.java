package io.github.azotorp.wowuisync.common.path;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Case-insensitive glob exclusions (DESIGN.md §14). Supports {@code **} (any number of segments),
 * {@code *} (within one segment) and {@code ?} (one character within a segment).
 */
public final class Exclusions {
    public static final List<String> DEFAULTS = List.of(
            "WTF/Config.wtf",
            "WTF/gamecontrollerdb.txt",
            "WTF/GamePadConfig_*.json",
            "WTF/**/*.bak",
            "WTF/**/*.old",
            "Interface/AddOns/**/.git/**",
            "**/Thumbs.db",
            "**/desktop.ini",
            "**/.DS_Store");

    private final List<String> globs;
    private final List<Pattern> patterns;

    public Exclusions(Collection<String> globs) {
        this.globs = List.copyOf(globs);
        this.patterns = new ArrayList<>();
        for (String g : this.globs) {
            String t = g.trim();
            if (!t.isEmpty()) patterns.add(Pattern.compile(toRegex(t), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
    }

    public static Exclusions defaults() {
        return new Exclusions(DEFAULTS);
    }

    public List<String> globs() {
        return globs;
    }

    public boolean isExcluded(String path) {
        for (Pattern p : patterns) if (p.matcher(path).matches()) return true;
        return false;
    }

    static String toRegex(String glob) {
        String g = glob.replace('\\', '/');
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < g.length()) {
            char c = g.charAt(i);
            if (c == '*') {
                boolean dbl = i + 1 < g.length() && g.charAt(i + 1) == '*';
                if (dbl) {
                    boolean slashAfter = i + 2 < g.length() && g.charAt(i + 2) == '/';
                    if (slashAfter) {
                        sb.append("(?:.*/)?");
                        i += 3;
                    } else {
                        sb.append(".*");
                        i += 2;
                    }
                } else {
                    sb.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                sb.append("[^/]");
                i++;
            } else {
                if ("\\.[]{}()+-^$|".indexOf(c) >= 0) sb.append('\\');
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
