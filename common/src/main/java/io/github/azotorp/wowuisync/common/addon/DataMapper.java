package io.github.azotorp.wowuisync.common.addon;

import io.github.azotorp.wowuisync.common.path.SyncPaths;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps synced paths to addon files, addon data (SavedVariables), or general settings (DESIGN.md §15.2–15.3). */
public final class DataMapper {
    public enum Kind { ADDON_FILE, ADDON_DATA, GENERAL }

    public record Classification(Kind kind, String folder) {}

    private static final Pattern SV = Pattern.compile(
            "(?i)WTF/Account/[^/]+/(?:[^/]+/[^/]+/)?SavedVariables/([^/]+?)\\.lua(?:\\.bak)?");

    private DataMapper() {}

    /** Folder name a SavedVariables file is named after, or null if the path is not a SavedVariables file. */
    public static String savedVariablesFolder(String path) {
        Matcher m = SV.matcher(path);
        return m.matches() ? m.group(1) : null;
    }

    /**
     * Classifies a path. {@code addonFoldersLc} is the set of known addon folder names (lower case);
     * SavedVariables of unknown folders (e.g. {@code Blizzard_*}) are general settings.
     */
    public static Classification classify(String path, Set<String> addonFoldersLc) {
        String folder = SyncPaths.addonFolder(path);
        if (folder != null) return new Classification(Kind.ADDON_FILE, folder);
        String sv = savedVariablesFolder(path);
        if (sv != null && addonFoldersLc.contains(sv.toLowerCase(Locale.ROOT))) {
            return new Classification(Kind.ADDON_DATA, sv);
        }
        return new Classification(Kind.GENERAL, null);
    }
}
