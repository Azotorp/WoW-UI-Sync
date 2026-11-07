package io.github.azotorp.wowuisync.common.util;

import java.util.Locale;

/** Username rules (DESIGN.md §6). */
public final class Usernames {
    public static final String ROOT = "root";

    private Usernames() {}

    public static boolean isValid(String u) {
        return u != null && u.matches("[A-Za-z0-9_-]{3,32}");
    }

    public static String validationMessage(String u) {
        if (u == null || u.isEmpty()) return "Enter a username.";
        if (u.length() < 3 || u.length() > 32) return "Usernames are 3 to 32 characters.";
        if (!u.matches("[A-Za-z0-9_-]+")) return "Usernames may only use letters, digits, '_' and '-'.";
        return null;
    }

    public static String lc(String u) {
        return u.toLowerCase(Locale.ROOT);
    }

    public static boolean isRoot(String u) {
        return u != null && lc(u).equals(ROOT);
    }
}
