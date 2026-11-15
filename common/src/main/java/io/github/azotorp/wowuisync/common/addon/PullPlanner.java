package io.github.azotorp.wowuisync.common.addon;
import io.github.azotorp.wowuisync.common.manifest.*;
import io.github.azotorp.wowuisync.common.path.SyncPaths;
import java.util.*;
/** Scoped synchronization. Unselected paths survive; source/local catalog provenance never mixes. */
public final class PullPlanner {
    private PullPlanner() {}
    public static Manifest target(Manifest source, Manifest local, PullSelection sel, PullMode mode) {
        return target(source,local,sel,Map.of(),Map.of());
    }
    public static Manifest target(Manifest source, Manifest local, PullSelection sel,
                                   Map<String,TocInfo> sourceTocs, Map<String,TocInfo> localTocs) {
        AddonCatalog src = new AddonCatalog(source,sourceTocs), dst = new AddonCatalog(local,localTocs);
        Set<String> files = lower(sel.addons()), data = lower(sel.dataAddons()), orphan = lower(sel.orphanData());
        Set<String> remove = lower(sel.removeAddons());
        Set<String> sourceFolders = lower(AddonGrouper.folders(source));
        if (!Collections.disjoint(remove, sourceFolders)) throw new IllegalArgumentException("Only local-only folders may be explicitly removed");
        Map<String,ManifestEntry> out = new LinkedHashMap<>();
        for (ManifestEntry e : local.entries()) {
            String folder = SyncPaths.addonFolder(e.path());
            String token = dst.token(e.path());
            boolean replace = folder != null ? files.contains(lc(folder)) && sourceFolders.contains(lc(folder)) || remove.contains(lc(folder))
                    : token == null ? sel.general() : token.startsWith("@data:") ? orphan.contains(lc(token)) : data.contains(lc(token));
            // Missing source data is never a deletion instruction. Only exact selected source paths overwrite data.
            if (folder == null || !replace) out.put(SyncPaths.key(e.path()),e);
        }
        for (ManifestEntry e : source.entries()) {
            String folder = SyncPaths.addonFolder(e.path()), token = src.token(e.path());
            boolean take = folder != null ? files.contains(lc(folder))
                    : token == null ? sel.general() : token.startsWith("@data:") ? orphan.contains(lc(token)) : data.contains(lc(token));
            if (take) out.put(SyncPaths.key(e.path()),e);
        }
        return new Manifest(new ArrayList<>(out.values()));
    }
    public static ManifestDiff plan(Manifest source, Manifest local, PullSelection sel, PullMode mode) {
        return ManifestDiff.between(local,target(source,local,sel,mode));
    }
    public static ManifestDiff plan(Manifest source, Manifest local, PullSelection sel, Map<String,TocInfo> sourceTocs, Map<String,TocInfo> localTocs) {
        return ManifestDiff.between(local,target(source,local,sel,sourceTocs,localTocs));
    }
    public static boolean selectsEverything(Manifest source, PullSelection sel, PullMode mode) {
        // Kept only for old callers; runtime determines exactness by fingerprint comparison.
        Set<String> files=lower(sel.addons()),data=lower(sel.dataAddons());
        return sel.general() && AddonGrouper.folders(source).stream().allMatch(f->files.contains(lc(f))&&data.contains(lc(f)))
                && source.entries().stream().noneMatch(e -> DataMapper.savedVariablesFolder(e.path()) != null);
    }
    private static String lc(String s) { return s.toLowerCase(Locale.ROOT); }
    private static Set<String> lower(Collection<String> in) { Set<String> out=new HashSet<>(); in.forEach(s->out.add(lc(s))); return out; }
}
