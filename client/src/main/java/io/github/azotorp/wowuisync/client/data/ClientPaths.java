package io.github.azotorp.wowuisync.client.data;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** {@code wow-ui-sync-data/} next to the client jar (DESIGN.md §17.1). */
public record ClientPaths(Path root) {
    public static ClientPaths locate() {
        String override = System.getProperty("wowuisync.data");
        if (override != null) return new ClientPaths(Paths.get(override).toAbsolutePath());
        Path base;
        try {
            Path p = Paths.get(ClientPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            base = Files.isRegularFile(p) ? p.getParent() : Paths.get("").toAbsolutePath();
        } catch (URISyntaxException | RuntimeException e) {
            base = Paths.get("").toAbsolutePath();
        }
        return new ClientPaths(base.resolve("wow-ui-sync-data"));
    }

    /** Raw chunk staging is session-temporary; an apply journal protects it across restarts. */
    public void cleanTransient() throws java.io.IOException {
        Path operations=root.resolve("operations");
        if(Files.isDirectory(operations))try(var entries=Files.list(operations)) {
            if(entries.anyMatch(p->p.getFileName().toString().endsWith(".json")))return;
        }
        io.github.azotorp.wowuisync.client.local.FileOps.deleteTree(stagedObjects());
        io.github.azotorp.wowuisync.client.local.FileOps.deleteTree(operations);
    }
    public Path config() { return root.resolve("client.json"); }
    public Path db() { return root.resolve("client.db"); }
    public Path dbBackups() { return root.resolve("db-backups"); }
    public Path partial() { return root.resolve("partial"); }
    public Path logs() { return root.resolve("logs"); }
    public Path bases() { return root.resolve("bases"); }
    /** Completed downloaded objects waiting to be applied. */
    public Path stagedObjects() { return partial().resolve("objects"); }
}
