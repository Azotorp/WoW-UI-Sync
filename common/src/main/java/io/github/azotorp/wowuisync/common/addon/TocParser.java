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
        String title = null, curse = null, wago = null, wowi = null, group = null;
        List<String> optional = new ArrayList<>(), saved = new ArrayList<>(), character = new ArrayList<>();
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
                case "dependencies", "requireddeps", "requireddependencies", "dep", "deps" -> {
                    for (String d : value.split(",")) {
                        String t = d.trim();
                        if (!t.isEmpty()) deps.add(t);
                    }
                }
                case "optionaldeps", "optionaldependencies" -> add(optional, value);
                case "savedvariables" -> add(saved, value);
                case "savedvariablespercharacter" -> add(character, value);
                case "group" -> { if (group == null && !value.isBlank()) group = stripColors(value); }
                case "x-curse-project-id" -> curse = value;
                case "x-wago-id" -> wago = value;
                case "x-wowi-id" -> wowi = value;
                default -> {
                    // ignore
                }
            }
        }
        if (title == null || title.isBlank()) title = folder;
        return new TocInfo(folder, title, dedup(deps), blankToNull(curse), blankToNull(wago), blankToNull(wowi),
                blankToNull(group), dedup(optional), dedup(saved), dedup(character));
    }

    /** Removes WoW color codes ({@code |cAARRGGBB}, {@code |r}), textures and atlas tags. */
    public static String stripColors(String s) {
        String r = s.replaceAll("(?i)\\|c[0-9A-Fa-f]{8}", "")
                .replaceAll("(?i)\\|r", "")
                .replaceAll("(?i)\\|T[^|]*\\|t", "")
                .replaceAll("(?i)\\|A[^|]*\\|a", "");
        return r.replaceAll("\\s+", " ").trim();
    }

    private static void add(List<String> out, String value) {
        for (String part : value.split(",")) if (!part.trim().isEmpty()) out.add(part.trim());
    }
    private static List<String> dedup(List<String> in) {
        java.util.Map<String, String> unique = new java.util.LinkedHashMap<>();
        for (String s : in) unique.putIfAbsent(s.toLowerCase(Locale.ROOT), s);
        return List.copyOf(unique.values());
    }
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
