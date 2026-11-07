package io.github.azotorp.wowuisync.common.util;

import java.util.Locale;

/** Human-readable sizes. */
public final class Sizes {
    private Sizes() {}

    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int i = -1;
        do {
            v /= 1024;
            i++;
        } while (v >= 1024 && i < units.length - 1);
        return String.format(Locale.ROOT, v >= 100 ? "%.0f %s" : v >= 10 ? "%.1f %s" : "%.2f %s", v, units[i]);
    }

    public static long mb(long bytes) {
        return (bytes + (1 << 20) - 1) >> 20;
    }

    public static String duration(long seconds) {
        if (seconds < 0) return "--";
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }
}
