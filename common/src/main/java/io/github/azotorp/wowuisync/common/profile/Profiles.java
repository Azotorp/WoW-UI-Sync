package io.github.azotorp.wowuisync.common.profile;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Profile (game flavor) rules (DESIGN.md §13). */
public final class Profiles {
    /** Profile ids the server accepts. Enabling a flavor later = adding it here. */
    public static final Set<String> ENABLED = Set.of("forever");

    /** Known flavor folder display names. */
    public static final Map<String, String> DISPLAY = Map.of(
            "forever", "Forever",
            "retail", "Retail",
            "anniversary", "Anniversary",
            "classic_era", "Classic Era",
            "classic", "Classic",
            "ptr", "PTR",
            "beta", "Beta");

    public static final List<String> WOW_EXECUTABLE_NAMES = List.of(
            "Wow.exe", "WowClassic.exe", "WowClassicT.exe", "WowT.exe", "WowB.exe",
            "World of Warcraft", "World of Warcraft Classic");

    private Profiles() {}

    /** {@code _forever_} → {@code forever}. */
    public static String profileIdFromFolder(String folderName) {
        String s = folderName;
        while (s.startsWith("_")) s = s.substring(1);
        while (s.endsWith("_")) s = s.substring(0, s.length() - 1);
        return s.toLowerCase(Locale.ROOT);
    }

    public static boolean isValidProfileId(String id) {
        return id != null && id.matches("[a-z0-9_]{1,40}");
    }

    public static boolean isEnabled(String id) {
        return ENABLED.contains(id);
    }

    public static String displayName(String id) {
        String d = DISPLAY.get(id);
        if (d != null) return d;
        if (id.isEmpty()) return id;
        return Character.toUpperCase(id.charAt(0)) + id.substring(1).replace('_', ' ');
    }
}
