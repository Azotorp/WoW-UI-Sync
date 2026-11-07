package io.github.azotorp.wowuisync.client.data;

import io.github.azotorp.wowuisync.common.hash.HashCacheEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Client database ({@code client.db}, SQLite) with migrations (DESIGN.md §17.1). All methods synchronized. */
public final class ClientDb implements AutoCloseable {
    private static final List<String[]> MIGRATIONS = List.<String[]>of(
            new String[] {
                    "CREATE TABLE connections (id TEXT PRIMARY KEY, name TEXT NOT NULL, host TEXT NOT NULL, port INTEGER NOT NULL, "
                            + "username TEXT NOT NULL, auth_type TEXT NOT NULL, password TEXT, key_path TEXT, created_at INTEGER NOT NULL)",
                    "CREATE TABLE pinned (host_port TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, pinned_at INTEGER NOT NULL)",
                    "CREATE TABLE hash_cache (flavor_path TEXT NOT NULL, path TEXT NOT NULL, size INTEGER NOT NULL, mtime INTEGER NOT NULL, "
                            + "sha256 TEXT NOT NULL, PRIMARY KEY(flavor_path, path))",
                    "CREATE TABLE bases (host_port TEXT NOT NULL, account_id TEXT NOT NULL, profile_id TEXT NOT NULL, "
                            + "fingerprint TEXT NOT NULL, PRIMARY KEY(host_port, account_id, profile_id))",
                    "CREATE TABLE applied (flavor_path TEXT PRIMARY KEY, account_name TEXT NOT NULL, account_id TEXT, host_port TEXT, "
                            + "applied_at INTEGER NOT NULL)",
                    "CREATE TABLE backups (flavor_path TEXT NOT NULL, suffix TEXT NOT NULL, account TEXT NOT NULL, created_at INTEGER NOT NULL, "
                            + "reason TEXT NOT NULL, source_account TEXT, file_count INTEGER NOT NULL, size_bytes INTEGER NOT NULL, "
                            + "PRIMARY KEY(flavor_path, suffix))",
                    "CREATE TABLE sample_cache (sha256 TEXT PRIMARY KEY, compressible INTEGER NOT NULL)"
            });

    public static final int SCHEMA_VERSION = MIGRATIONS.size();

    private final Connection conn;

    private ClientDb(Connection c) {
        this.conn = c;
    }

    public static ClientDb open(Path file, Path backupDir) throws SQLException, IOException {
        Files.createDirectories(file.getParent());
        boolean existed = Files.exists(file);
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA busy_timeout=10000");
            s.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
        }
        ClientDb db = new ClientDb(c);
        db.migrate(backupDir, existed);
        return db;
    }

    private synchronized void migrate(Path backupDir, boolean existed) throws SQLException, IOException {
        int v;
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT MAX(version) FROM schema_version")) {
            v = rs.next() ? rs.getInt(1) : 0;
        }
        if (v > SCHEMA_VERSION) throw new SQLException("client.db is from a newer version of WoW UI Sync.");
        if (v == SCHEMA_VERSION) return;
        if (existed && v > 0) {
            Files.createDirectories(backupDir);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            try (Statement s = conn.createStatement()) {
                s.execute("VACUUM INTO '" + backupDir.resolve("client-v" + v + "-" + ts + ".db").toAbsolutePath().toString().replace("'", "''") + "'");
            }
        }
        conn.setAutoCommit(false);
        try {
            for (int i = v; i < SCHEMA_VERSION; i++) {
                try (Statement s = conn.createStatement()) {
                    for (String sql : MIGRATIONS.get(i)) s.execute(sql);
                    s.execute("DELETE FROM schema_version");
                    s.execute("INSERT INTO schema_version(version) VALUES (" + (i + 1) + ")");
                }
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // ignore
        }
    }

    // ------------------------------------------------------------------ connections

    public synchronized List<ConnectionRecord> connections() throws SQLException {
        List<ConnectionRecord> out = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, name, host, port, username, auth_type, password, key_path FROM connections ORDER BY lower(name)")) {
            while (rs.next()) {
                out.add(new ConnectionRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8)));
            }
        }
        return out;
    }

    public synchronized Optional<ConnectionRecord> connection(String id) throws SQLException {
        for (ConnectionRecord c : connections()) if (c.id().equals(id)) return Optional.of(c);
        return Optional.empty();
    }

    public synchronized void saveConnection(ConnectionRecord c) throws SQLException {
        exec("INSERT OR REPLACE INTO connections(id, name, host, port, username, auth_type, password, key_path, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,COALESCE((SELECT created_at FROM connections WHERE id=?),?))",
                c.id(), c.name(), c.host(), c.port(), c.username(), c.authType(), c.password(), c.keyPath(), c.id(), System.currentTimeMillis());
    }

    public synchronized void deleteConnection(String id) throws SQLException {
        exec("DELETE FROM connections WHERE id=?", id);
    }

    // ------------------------------------------------------------------ pinned fingerprints

    public synchronized String pinned(String hostPort) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement("SELECT fingerprint FROM pinned WHERE host_port=?")) {
            p.setString(1, hostPort);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public synchronized void pin(String hostPort, String fingerprint) throws SQLException {
        exec("INSERT OR REPLACE INTO pinned(host_port, fingerprint, pinned_at) VALUES (?,?,?)", hostPort, fingerprint, System.currentTimeMillis());
    }

    public synchronized void unpin(String hostPort) throws SQLException {
        exec("DELETE FROM pinned WHERE host_port=?", hostPort);
    }

    // ------------------------------------------------------------------ hash cache

    public synchronized Map<String, HashCacheEntry> hashCache(String flavorPath) throws SQLException {
        Map<String, HashCacheEntry> out = new HashMap<>();
        try (PreparedStatement p = conn.prepareStatement("SELECT path, size, mtime, sha256 FROM hash_cache WHERE flavor_path=?")) {
            p.setString(1, flavorPath);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), new HashCacheEntry(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4)));
            }
        }
        return out;
    }

    /** Replaces the cache for a flavor folder with {@code entries}. */
    public synchronized void replaceHashCache(String flavorPath, Collection<HashCacheEntry> entries) throws SQLException {
        conn.setAutoCommit(false);
        try {
            exec("DELETE FROM hash_cache WHERE flavor_path=?", flavorPath);
            try (PreparedStatement p = conn.prepareStatement("INSERT INTO hash_cache(flavor_path, path, size, mtime, sha256) VALUES (?,?,?,?,?)")) {
                int n = 0;
                for (HashCacheEntry e : entries) {
                    p.setString(1, flavorPath);
                    p.setString(2, e.path());
                    p.setLong(3, e.size());
                    p.setLong(4, e.lastModified());
                    p.setString(5, e.sha256());
                    p.addBatch();
                    if (++n % 5000 == 0) p.executeBatch();
                }
                p.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    // ------------------------------------------------------------------ bases

    public synchronized String base(String hostPort, String accountId, String profileId) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement("SELECT fingerprint FROM bases WHERE host_port=? AND account_id=? AND profile_id=?")) {
            p.setString(1, hostPort);
            p.setString(2, accountId);
            p.setString(3, profileId);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public synchronized void setBase(String hostPort, String accountId, String profileId, String fingerprint) throws SQLException {
        exec("INSERT OR REPLACE INTO bases(host_port, account_id, profile_id, fingerprint) VALUES (?,?,?,?)", hostPort, accountId, profileId, fingerprint);
    }

    /** Clears every base for a profile (flavor folder), on every server and account. */
    public synchronized void clearBases(String profileId) throws SQLException {
        exec("DELETE FROM bases WHERE profile_id=?", profileId);
    }

    // ------------------------------------------------------------------ applied account

    public record Applied(String accountName, String accountId, String hostPort, long appliedAt) {}

    public synchronized Applied applied(String flavorPath) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement("SELECT account_name, account_id, host_port, applied_at FROM applied WHERE flavor_path=?")) {
            p.setString(1, flavorPath);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? new Applied(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)) : null;
            }
        }
    }

    public synchronized void setApplied(String flavorPath, String accountName, String accountId, String hostPort) throws SQLException {
        exec("INSERT OR REPLACE INTO applied(flavor_path, account_name, account_id, host_port, applied_at) VALUES (?,?,?,?,?)",
                flavorPath, accountName, accountId, hostPort, System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ backups

    public synchronized List<BackupRecord> backups(String flavorPath) throws SQLException {
        List<BackupRecord> out = new ArrayList<>();
        try (PreparedStatement p = conn.prepareStatement("SELECT flavor_path, suffix, account, created_at, reason, source_account, file_count, "
                + "size_bytes FROM backups WHERE flavor_path=? ORDER BY created_at DESC")) {
            p.setString(1, flavorPath);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    out.add(new BackupRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5),
                            rs.getString(6), rs.getLong(7), rs.getLong(8)));
                }
            }
        }
        return out;
    }

    public synchronized void addBackup(BackupRecord b) throws SQLException {
        exec("INSERT OR REPLACE INTO backups(flavor_path, suffix, account, created_at, reason, source_account, file_count, size_bytes) "
                        + "VALUES (?,?,?,?,?,?,?,?)", b.flavorPath(), b.suffix(), b.account(), b.createdAt(), b.reason(), b.sourceAccount(),
                b.fileCount(), b.sizeBytes());
    }

    public synchronized void deleteBackup(String flavorPath, String suffix) throws SQLException {
        exec("DELETE FROM backups WHERE flavor_path=? AND suffix=?", flavorPath, suffix);
    }

    // ------------------------------------------------------------------ sample cache

    public synchronized Map<String, Boolean> sampleCache() throws SQLException {
        Map<String, Boolean> out = new HashMap<>();
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT sha256, compressible FROM sample_cache")) {
            while (rs.next()) out.put(rs.getString(1), rs.getInt(2) != 0);
        }
        return out;
    }

    public synchronized void saveSampleCache(Map<String, Boolean> m) throws SQLException {
        conn.setAutoCommit(false);
        try (PreparedStatement p = conn.prepareStatement("INSERT OR REPLACE INTO sample_cache(sha256, compressible) VALUES (?,?)")) {
            for (Map.Entry<String, Boolean> e : m.entrySet()) {
                p.setString(1, e.getKey());
                p.setInt(2, e.getValue() ? 1 : 0);
                p.addBatch();
            }
            p.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void exec(String sql, Object... args) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                if (a == null) p.setNull(i + 1, Types.NULL);
                else if (a instanceof Integer n) p.setInt(i + 1, n);
                else if (a instanceof Long n) p.setLong(i + 1, n);
                else p.setString(i + 1, a.toString());
            }
            p.executeUpdate();
        }
    }
}
