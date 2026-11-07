package io.github.azotorp.wowuisync.client.wow;

import io.github.azotorp.wowuisync.common.profile.Profiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Finds flavor folders in an install root (DESIGN.md §13). */
public final class FlavorDetector {
    private FlavorDetector() {}

    public static List<Flavor> detect(Path installRoot) {
        List<Flavor> out = new ArrayList<>();
        if (installRoot == null || !Files.isDirectory(installRoot)) return out;
        try (Stream<Path> s = Files.list(installRoot)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isDirectory)::iterator) {
                String name = p.getFileName().toString();
                boolean underscored = name.length() > 2 && name.startsWith("_") && name.endsWith("_");
                boolean marker = hasMarker(p);
                boolean content = Files.isDirectory(p.resolve("WTF")) || Files.isDirectory(p.resolve("Interface"));
                if (marker && content) out.add(new Flavor(name, p, Profiles.profileIdFromFolder(name), true));
                else if (underscored) out.add(new Flavor(name, p, Profiles.profileIdFromFolder(name), false));
            }
        } catch (IOException e) {
            return out;
        }
        out.sort(Comparator.comparing((Flavor f) -> !f.enabled()).thenComparing(f -> f.folderName().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static boolean hasMarker(Path dir) {
        if (Files.exists(dir.resolve(".flavor.info"))) return true;
        try (Stream<Path> s = Files.list(dir)) {
            return s.anyMatch(p -> {
                String n = p.getFileName().toString();
                String l = n.toLowerCase(Locale.ROOT);
                return (l.startsWith("wow") && l.endsWith(".exe")) || (n.startsWith("World of Warcraft") && l.endsWith(".app"));
            });
        } catch (IOException e) {
            return false;
        }
    }

    /** Suggested install root: macOS only (DESIGN.md §13). */
    public static Path suggestedRoot() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            Path p = Paths.get("/Applications/World of Warcraft");
            return p;
        }
        return null;
    }
}
