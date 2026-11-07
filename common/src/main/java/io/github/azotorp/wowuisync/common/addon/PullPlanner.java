package io.github.azotorp.wowuisync.common.addon;

import io.github.azotorp.wowuisync.common.manifest.Manifest;
import io.github.azotorp.wowuisync.common.manifest.ManifestDiff;
import io.github.azotorp.wowuisync.common.manifest.ManifestEntry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Selective pull plan rules (DESIGN.md §12.2), as pure functions shared by client and server. */
public final class PullPlanner {
    private PullPlanner() {}

    /** The local state after the pull. */
    public static Manifest target(Manifest source, Manifest local, PullSelection sel, PullMode mode) {
        Set<String> known = new HashSet<>();
        for (String f : AddonGrouper.folders(source)) known.add(lc(f));
        for (String f : AddonGrouper.folders(local)) known.add(lc(f));
        Set<String> addons = lcSet(sel.addons());
        Set<String> data = lcSet(sel.dataAddons());

        List<ManifestEntry> out = new ArrayList<>();
        for (ManifestEntry e : local.entries()) {
            DataMapper.Classification c = DataMapper.classify(e.path(), known);
            boolean keep = switch (c.kind()) {
                case ADDON_FILE -> !addons.contains(lc(c.folder())) && mode == PullMode.KEEP;
                case ADDON_DATA -> !data.contains(lc(c.folder()));
                case GENERAL -> !sel.general();
            };
            if (keep) out.add(e);
        }
        for (ManifestEntry e : source.entries()) {
            DataMapper.Classification c = DataMapper.classify(e.path(), known);
            boolean take = switch (c.kind()) {
                case ADDON_FILE -> addons.contains(lc(c.folder()));
                case ADDON_DATA -> data.contains(lc(c.folder()));
                case GENERAL -> sel.general();
            };
            if (take) out.add(e);
        }
        return new Manifest(out);
    }

    public static ManifestDiff plan(Manifest source, Manifest local, PullSelection sel, PullMode mode) {
        return ManifestDiff.between(local, target(source, local, sel, mode));
    }

    /** True if the selection is "everything" for this source (used to decide whether the base is updated). */
    public static boolean selectsEverything(Manifest source, PullSelection sel, PullMode mode) {
        if (mode != PullMode.REPLACE || !sel.general()) return false;
        Set<String> addons = lcSet(sel.addons());
        Set<String> data = lcSet(sel.dataAddons());
        for (String f : AddonGrouper.folders(source)) {
            if (!addons.contains(lc(f)) || !data.contains(lc(f))) return false;
        }
        return true;
    }

    private static Set<String> lcSet(List<String> l) {
        Set<String> s = new HashSet<>();
        for (String x : l) s.add(lc(x));
        return s;
    }

    private static String lc(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
