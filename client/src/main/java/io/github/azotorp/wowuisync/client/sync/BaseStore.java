package io.github.azotorp.wowuisync.client.sync;

import io.github.azotorp.wowuisync.client.data.ClientDb;
import io.github.azotorp.wowuisync.client.data.ClientPaths;
import io.github.azotorp.wowuisync.common.hash.Hashing;
import io.github.azotorp.wowuisync.common.manifest.Manifest;
import io.github.azotorp.wowuisync.common.manifest.ManifestIO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

/**
 * Bases (DESIGN.md §11.3): per (server, account, profile) the fingerprint this device last fully synced with.
 * The base manifest is kept too, so later pushes can send a delta.
 */
public final class BaseStore {
    private final ClientDb db;
    private final ClientPaths paths;

    public BaseStore(ClientDb db, ClientPaths paths) {
        this.db = db;
        this.paths = paths;
    }

    private Path file(String hostPort, String accountId, String profileId) {
        String k = Hashing.sha256Hex((hostPort + "|" + accountId + "|" + profileId).getBytes(StandardCharsets.UTF_8));
        return paths.bases().resolve(k + ".manifest");
    }

    public String fingerprint(String hostPort, String accountId, String profileId) throws SQLException {
        return db.base(hostPort, accountId, profileId);
    }

    /** The base manifest, or null if not stored or not matching the base fingerprint. */
    public Manifest manifest(String hostPort, String accountId, String profileId) {
        try {
            String fp = fingerprint(hostPort, accountId, profileId);
            Path f = file(hostPort, accountId, profileId);
            if (fp == null || !Files.exists(f)) return null;
            Manifest m = ManifestIO.decode(Files.readAllBytes(f));
            return fp.equals(m.fingerprint()) ? m : null;
        } catch (Exception e) {
            return null;
        }
    }

    public void set(String hostPort, String accountId, String profileId, Manifest m) throws SQLException, IOException {
        Files.createDirectories(paths.bases());
        ManifestIO.write(file(hostPort, accountId, profileId), ManifestIO.encode(m));
        db.setBase(hostPort, accountId, profileId, m.fingerprint());
    }

    /** Clears every base for the profile (flavor folder). Manifest files are left; they no longer match. */
    public void clearAll(String profileId) throws SQLException {
        db.clearBases(profileId);
    }
}
