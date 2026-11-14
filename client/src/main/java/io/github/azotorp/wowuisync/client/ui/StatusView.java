package io.github.azotorp.wowuisync.client.ui;

import io.github.azotorp.wowuisync.client.ClientApp;

import java.awt.Color;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** What the status badge and line show. */
public record StatusView(String badge, Color color, String line, boolean detailsAvailable) {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm:ss a", Locale.ENGLISH);

    public static StatusView message(String badge, Color c, String line) {
        return new StatusView(badge, c, line, false);
    }

    public static StatusView notConnected(ClientApp app) {
        return message("Not connected", Theme.GRAY, "Browse local addons and legacy backups offline. Connect for snapshots and changes.");
    }

    public static String when(long epochMillis) {
        return WHEN.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    public static String whenLong(long epochMillis) {
        return LONG.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }
}
