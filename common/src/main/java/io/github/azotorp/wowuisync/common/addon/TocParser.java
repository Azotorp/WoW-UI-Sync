package io.github.azotorp.wowuisync.common.addon;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Minimal .toc reader. */
public final class TocParser {
    private TocParser() {}

    public static TocInfo parse(String folder, byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
        String title = null, curse = null, wago = null, wowi = null;
        List<String> deps = new ArrayList<>();
        for (String raw : text.split("\r?\n|\r")) {
            String line = raw.trim();
            if (!line.startsWith("##")) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(2, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            switch (key) {
                case "title" -> {
                    if (title == null) title = stripColors(value);
                }
                case "dependencies", "requireddeps", "optionaldeps", "dep", "deps" -> {
                    for (String d : value.split(",")) {
                        String t = d.trim();
                        if (!t.isEmpty()) deps.add(t);
                    }
                }
                case "x-curse-project-id" -> curse = value;
                case "x-wago-id" -> wago = value;
                case "x-wowi-id" -> wowi = value;
                default -> {
                    // ignore
                }
            }
        }
        if (title == null || title.isBlank()) title = folder;
        return new TocInfo(folder, title, List.copyOf(deps), blankToNull(curse), blankToNull(wago), blankToNull(wowi));
    }

    /** Removes WoW color codes ({@code |cAARRGGBB}, {@code |r}), textures and atlas tags. */
    public static String stripColors(String s) {
        String r = s.replaceAll("\\|c[0-9A-Fa-f]{8}", "")
                .replaceAll("\\|r", "")
                .replaceAll("\\|T[^|]*\\|t", "")
                .replaceAll("\\|A[^|]*\\|a", "");
        return r.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
