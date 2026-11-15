package io.github.azotorp.wowuisync.common.addon;

import io.github.azotorp.wowuisync.common.manifest.*;
import io.github.azotorp.wowuisync.common.path.SyncPaths;
import java.util.*;

/** One folder per row, with separately selectable orphan/shared data and explainable family metadata. */
public final class AddonCatalog {
    public record Family(String name, String source) {}
    public record Row(String id, String name, String folder, Family family, String dependencies,
                      long size, long dataSize, boolean dataOnly, List<String> locations) {}
    private final Map<String,TocInfo> tocs;
    private final Map<String,String> folders = new TreeMap<>();
    private final Map<String,Set<String>> owners = new HashMap<>();
    private final Manifest manifest;
    public AddonCatalog(Manifest manifest, Map<String,TocInfo> tocs) {
        this.manifest = manifest;
        this.tocs = new HashMap<>();
        tocs.forEach((k,v) -> this.tocs.put(lc(k),v));
        for (String f : AddonGrouper.folders(manifest)) folders.put(lc(f), f);
        for (String f : folders.values()) {
            TocInfo t = this.tocs.get(lc(f));
            if (t != null) {
                for (String v : t.savedVariables()) declare("account:" + v, f);
                for (String v : t.perCharacterVariables()) declare("character:" + v, f);
            }
        }
    }
    private void declare(String key, String folder) {
        owners.computeIfAbsent(lc(key), k -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)).add(folder);
    }
    public static String lc(String s) { return s.toLowerCase(Locale.ROOT); }
    public static String variableKey(String path) {
        String v = DataMapper.savedVariablesFolder(path);
        if (v == null) return null;
        String[] pieces = path.split("/");
        return (pieces.length == 5 ? "account:" : "character:") + v;
    }
    /** Selection token for a path; null means genuine general WTF settings. */
    public String token(String path) {
        String f = SyncPaths.addonFolder(path);
        if (f != null) return f;
        String key = variableKey(path);
        if (key == null) return null;
        Set<String> declared = owners.get(lc(key));
        if (declared != null && declared.size() == 1) return declared.iterator().next();
        if (declared == null) {
            String basename = DataMapper.savedVariablesFolder(path);
            // Legacy filename ownership remains valid, only after declaration ownership.
            if (folders.containsKey(lc(basename))) return folders.get(lc(basename));
        }
        return "@data:" + key;
    }
    public Family family(String f) {
        TocInfo t = tocs.get(lc(f));
        if (t != null && t.group() != null) {
            String anchor = folders.getOrDefault(lc(t.group()), t.group());
            return new Family(anchor, "Explicit TOC Group");
        }
        List<String> roots = new ArrayList<>();
        for (String root : folders.values()) {
            if (f.length() <= root.length() || !f.regionMatches(true,0,root,0,root.length())) continue;
            char next = f.charAt(root.length());
            boolean boundary = next == '_' || next == '-' || Character.isUpperCase(next)
                    && (Character.isLowerCase(f.charAt(root.length()-1))
                        || root.endsWith("UI") && next != 'I');
            if (boundary) roots.add(root);
        }
        roots.sort(Comparator.comparingInt(String::length).reversed().thenComparing(String.CASE_INSENSITIVE_ORDER));
        if (!roots.isEmpty()) {
            String root = roots.getFirst();
            boolean dependency = t != null && t.dependencies().stream().anyMatch(d -> d.equalsIgnoreCase(root));
            return new Family(root, dependency ? "Folder prefix + required dependency" : "Inferred folder prefix");
        }
        boolean isRoot = folders.values().stream().anyMatch(x -> !x.equals(f) && x.startsWith(f)
                && (x.charAt(f.length()) == '-' || x.charAt(f.length()) == '_' || Character.isUpperCase(x.charAt(f.length()))));
        return new Family(isRoot ? f : "", isRoot ? "Family root by folder prefix" : "Independent; no unambiguous family evidence");
    }
    public List<Row> rows() {
        Map<String,Long> fileBytes = new HashMap<>(), dataBytes = new HashMap<>();
        Map<String,List<String>> locations = new TreeMap<>();
        for (ManifestEntry e : manifest.entries()) {
            String key = token(e.path());
            if (key == null) continue;
            if (SyncPaths.addonFolder(e.path()) != null) fileBytes.merge(lc(key),e.size(),Long::sum);
            else { dataBytes.merge(lc(key),e.size(),Long::sum); locations.computeIfAbsent(key,k->new ArrayList<>()).add(e.path()); }
        }
        List<Row> out = new ArrayList<>();
        for (String f : folders.values()) {
            TocInfo t = tocs.get(lc(f));
            List<String> deps = new ArrayList<>();
            if (t != null) {
                for (String d : t.dependencies()) deps.add(d + (folders.containsKey(lc(d)) ? " (required)" : " (required, missing)"));
                for (String d : t.optionalDependencies()) deps.add(d + " (optional)");
            }
            out.add(new Row(f,t == null ? f : t.title(),f,family(f),String.join(", ",deps),
                    fileBytes.getOrDefault(lc(f),0L),dataBytes.getOrDefault(lc(f),0L),false,List.of()));
        }
        out.sort(Comparator.comparing(Row::name,String.CASE_INSENSITIVE_ORDER).thenComparing(Row::folder));
        List<Row> orphan = new ArrayList<>();
        locations.forEach((key, paths) -> {
            if (!key.startsWith("@data:")) return;
            String var = key.substring(key.indexOf(':',6)+1);
            Set<String> declared = owners.get(lc(key.substring(6)));
            String note = declared != null ? "Shared ownership: " + String.join(", ",declared)
                    : var.toLowerCase(Locale.ROOT).startsWith("blizzard_") ? "Blizzard (assumed)" : "Unrecognized SavedVariables";
            orphan.add(new Row(key,var + " [" + key.substring(6,key.indexOf(':',6)) + "]","",new Family("",note),note,
                    0,dataBytes.getOrDefault(lc(key),0L),true,List.copyOf(paths)));
        });
        orphan.sort(Comparator.comparing((Row x) -> !x.name().toLowerCase(Locale.ROOT).startsWith("blizzard_"))
                .thenComparing(Row::name,String.CASE_INSENSITIVE_ORDER));
        out.addAll(orphan);
        return List.copyOf(out);
    }
    public List<String> dependencyWarnings(Collection<String> selected) {
        Set<String> active = new HashSet<>(); selected.forEach(x -> active.add(lc(x)));
        List<String> out = new ArrayList<>();
        for (String f : selected) {
            TocInfo t = tocs.get(lc(f));
            if (t != null) for (String d : t.dependencies()) if (!active.contains(lc(d))) out.add(f + " requires " + d + " (missing or not selected)");
        }
        return out;
    }
}
