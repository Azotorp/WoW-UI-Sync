package io.github.azotorp.wowuisync.common;

import java.util.Locale;

/** Account roles (DESIGN.md §6). */
public enum Role {
    ROOT, SUPERUSER, USER;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Role parse(String s) {
        return valueOf(s.toUpperCase(Locale.ROOT));
    }

    public String display() {
        return switch (this) {
            case ROOT -> "Root";
            case SUPERUSER -> "Superuser";
            case USER -> "User";
        };
    }
}
