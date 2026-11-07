package io.github.azotorp.wowuisync.server.config;

import io.github.azotorp.wowuisync.common.json.Json;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Locates and loads {@code server.json}. */
public final class ConfigIO {
    private ConfigIO() {}

    /** Folder containing the running jar (or the working directory when run from classes). */
    public static Path jarDir() {
        try {
            Path p = Paths.get(ConfigIO.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(p) ? p.getParent() : Paths.get("").toAbsolutePath();
        } catch (URISyntaxException | RuntimeException e) {
            return Paths.get("").toAbsolutePath();
        }
    }

    public static Path jarSideConfig() {
        return jarDir().resolve("server.json");
    }

    public static ServerConfig read(Path file) throws IOException {
        ServerConfig c = Json.MAPPER.readValue(Files.readAllBytes(file), ServerConfig.class);
        c.validate();
        return c;
    }

    public static void write(Path file, ServerConfig c) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, Json.PRETTY.writeValueAsBytes(c));
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Resolves the data folder: {@code --data <path>} if given, else {@code dataDir} from server.json next to the jar.
     * Returns null if neither is available.
     */
    public static Path resolveDataDir(String dataArg) throws IOException {
        if (dataArg != null) return Paths.get(dataArg).toAbsolutePath().normalize();
        Path side = jarSideConfig();
        if (Files.isRegularFile(side)) {
            ServerConfig c = read(side);
            if (c.dataDir != null && !c.dataDir.isBlank()) return Paths.get(c.dataDir).toAbsolutePath().normalize();
        }
        return null;
    }

    /** Settings come from the data folder copy; falls back to the jar-side copy. */
    public static ServerConfig loadSettings(DataLayout layout) throws IOException {
        if (Files.isRegularFile(layout.config())) {
            ServerConfig c = read(layout.config());
            c.dataDir = layout.root().toString();
            return c;
        }
        Path side = jarSideConfig();
        if (Files.isRegularFile(side)) {
            ServerConfig c = read(side);
            c.dataDir = layout.root().toString();
            return c;
        }
        throw new IOException("No server.json found in " + layout.root() + ". Run install first.");
    }
}
