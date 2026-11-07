package io.github.azotorp.wowuisync.client.local;

import io.github.azotorp.wowuisync.common.addon.AddonGroup;
import io.github.azotorp.wowuisync.common.addon.AddonGrouper;
import io.github.azotorp.wowuisync.common.addon.DataMapper;
import io.github.azotorp.wowuisync.common.addon.TocInfo;
import io.github.azotorp.wowuisync.common.addon.TocParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Local addon manager (DESIGN.md §17.4): grouped addons with sizes, delete addon folders or their data. */
public final class AddonManager {
    /** One addon on disk. */
    public record LocalAddon(AddonGroup group, long addonBytes, List<Path> dataFiles, long dataBytes) {}

    private AddonManager() {}

    public static Path addonsDir(Path flavor) {
        return flavor.resolve("Interface").resolve("AddOns");
    }

    /** Reads every local addon folder and groups them. */
    public static List<LocalAddon> list(Path flavor) throws IOException {
        Path dir = addonsDir(flavor);
        List<String> folders = new ArrayList<>();
        Map<String, TocInfo> tocs = new HashMap<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s.filter(x -> Files.isDirectory(x, LinkOption.NOFOLLOW_LINKS))::iterator) {
                    String name = p.getFileName().toString();
                    folders.add(name);
                    Path toc = findToc(p, name);
                    if (toc != null && Files.size(toc) < (1 << 20)) tocs.put(name.toLowerCase(Locale.ROOT), TocParser.parse(name, Files.readAllBytes(toc)));
                }
            }
        }
        Set<String> known = new HashSet<>();
        for (String f : folders) known.add(f.toLowerCase(Locale.ROOT));
        Map<String, List<Path>> dataByFolder = new HashMap<>();
        Path account = flavor.resolve("WTF").resolve("Account");
        if (Files.isDirectory(account)) {
            try (Stream<Path> s = Files.walk(account)) {
                for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                    String rel = flavor.relativize(p).toString().replace('\\', '/');
                    DataMapper.Classification c = DataMapper.classify(rel, known);
                    if (c.kind() == DataMapper.Kind.ADDON_DATA) {
                        dataByFolder.computeIfAbsent(c.folder().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(p);
                    }
                }
            }
        }
        List<LocalAddon> out = new ArrayList<>();
        for (AddonGroup g : AddonGrouper.group(folders, tocs)) {
            long size = 0;
            List<Path> data = new ArrayList<>();
            long dataSize = 0;
            for (String f : g.folders()) {
                size += FileOps.size(dir.resolve(f)).bytes();
                for (Path d : dataByFolder.getOrDefault(f.toLowerCase(Locale.ROOT), List.of())) {
                    data.add(d);
                    dataSize += Files.size(d);
                }
            }
            out.add(new LocalAddon(g, size, data, dataSize));
        }
        return out;
    }

    static Path findToc(Path folder, String name) throws IOException {
        Path exact = folder.resolve(name + ".toc");
        if (Files.isRegularFile(exact)) return exact;
        try (Stream<Path> s = Files.list(folder)) {
            return s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".toc") && Files.isRegularFile(p))
                    .sorted().findFirst().orElse(null);
        }
    }

    public static void deleteAddon(Path flavor, LocalAddon a) throws IOException {
        for (String f : a.group().folders()) FileOps.deleteTree(addonsDir(flavor).resolve(f));
    }

    public static void deleteData(Path flavor, LocalAddon a) throws IOException {
        for (Path p : a.dataFiles()) Files.deleteIfExists(p);
    }
}
