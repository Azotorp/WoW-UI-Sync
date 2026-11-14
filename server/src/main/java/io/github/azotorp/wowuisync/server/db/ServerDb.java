package io.github.azotorp.wowuisync.server.db;

import io.github.azotorp.wowuisync.common.Role;
import io.github.azotorp.wowuisync.server.util.Logs;

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
import java.util.List;
import java.util.Optional;

/**
 * Server database ({@code wowuisync.db}, SQLite, WAL) with schema migrations (DESIGN.md §5.3, §5.6).
 * One connection; every method is synchronized.
 */
public final class ServerDb implements AutoCloseable {
    /** Each entry migrates from version i to i+1. */
    private static final List<String[]> MIGRATIONS = List.<String[]>of(
            new String[] {
                    "CREATE TABLE accounts (id TEXT PRIMARY KEY, username TEXT NOT NULL, username_lc TEXT NOT NULL UNIQUE, "
                            + "role TEXT NOT NULL, owner_id TEXT, email TEXT, auth_method TEXT NOT NULL, password_hash TEXT, "
                            + "password_salt TEXT, password_iterations INTEGER, key_type TEXT, created_at INTEGER NOT NULL, "
                            + "max_users INTEGER, quota_mb INTEGER, group_quota_mb INTEGER, failed_logins INTEGER NOT NULL DEFAULT 0, "
                            + "locked_until INTEGER, credential_version INTEGER NOT NULL DEFAULT 0)",
                    "CREATE INDEX accounts_owner ON accounts(owner_id)",
                    "CREATE TABLE invites (id TEXT PRIMARY KEY, secret_hash TEXT NOT NULL UNIQUE, role TEXT NOT NULL, "
                            + "owner_id TEXT NOT NULL, uses_left INTEGER, expires_at INTEGER, created_at INTEGER NOT NULL)",
                    "CREATE TABLE reset_codes (account_id TEXT PRIMARY KEY, code_hash TEXT NOT NULL, expires_at INTEGER NOT NULL, "
                            + "attempts_left INTEGER NOT NULL)",
                    "CREATE TABLE ip_failures (ip TEXT PRIMARY KEY, window_start INTEGER NOT NULL, count INTEGER NOT NULL, "
                            + "blocked_until INTEGER)",
                    "CREATE TABLE profile_state (account_id TEXT NOT NULL, profile_id TEXT NOT NULL, fingerprint TEXT, "
                            + "stored_bytes INTEGER NOT NULL DEFAULT 0, last_push_at INTEGER, last_push_device TEXT, "
                            + "last_push_by_account TEXT, PRIMARY KEY(account_id, profile_id))"
            }, new String[] {
                "CREATE TABLE request_cooldowns (key TEXT PRIMARY KEY, next_at INTEGER NOT NULL)",
                "CREATE TABLE verified_emails (account_id TEXT PRIMARY KEY, email TEXT NOT NULL, token_hash TEXT, expires_at INTEGER, verified INTEGER NOT NULL DEFAULT 0)"
            });

    public static final int SCHEMA_VERSION = MIGRATIONS.size();

    private final Connection conn;

    private ServerDb(Connection conn) {
        this.conn = conn;
    }

    /** Opens (creating if needed) and migrates. Takes a backup into {@code backupDir} before migrating an existing DB. */
    public static ServerDb open(Path file, Path backupDir) throws SQLException, IOException {
        boolean existed = Files.exists(file);
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=10000");
            s.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
        }
        ServerDb db = new ServerDb(c);
        try { db.migrate(file, backupDir, existed); } catch(SQLException | IOException | RuntimeException e) { c.close();throw e; }
        return db;
    }

    private synchronized void migrate(Path file, Path backupDir, boolean existed) throws SQLException, IOException {
        int v = schemaVersion();
        if (v > SCHEMA_VERSION) {
            throw new SQLException("Database schema version " + v + " is newer than this server (" + SCHEMA_VERSION
                    + "). Use a newer server jar.");
        }
        if(v>0) validateSchema(conn,v);
        if (v == SCHEMA_VERSION) return;
        if (existed && v > 0) {
            Files.createDirectories(backupDir);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            Path backup = backupDir.resolve("wowuisync-v" + v + "-" + ts + ".db");
            try (Statement s = conn.createStatement()) {
                s.execute("VACUUM INTO '" + backup.toAbsolutePath().toString().replace("'", "''") + "'");
            }
            Logs.LOG.info("Database backed up to " + backup + " before migration");
        }
        conn.setAutoCommit(false);
        try {
            for (int i = v; i < SCHEMA_VERSION; i++) {
                try (Statement s = conn.createStatement()) {
                    for (String sql : MIGRATIONS.get(i)) s.execute(sql);
                    s.execute("DELETE FROM schema_version");
                    s.execute("INSERT INTO schema_version(version) VALUES (" + (i + 1) + ")");
                }
                Logs.LOG.info("Database migrated to schema version " + (i + 1));
            }
            validateSchema(conn,SCHEMA_VERSION);
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public static void validateSchema(Connection c,int version) throws SQLException {
        if(version<1 || version>SCHEMA_VERSION)throw new SQLException("Unrecognized database schema "+version);
        try(var s=c.createStatement();var r=s.executeQuery("PRAGMA integrity_check")) {
            if(!r.next() || !"ok".equals(r.getString(1)))throw new SQLException("Database integrity check failed");
        }
        for(String q:List.of("SELECT "+ACCOUNT_COLS+",username_lc FROM accounts LIMIT 0",
                "SELECT id,secret_hash FROM invites LIMIT 0","SELECT account_id FROM reset_codes LIMIT 0",
                "SELECT ip FROM ip_failures LIMIT 0","SELECT account_id,profile_id,fingerprint FROM profile_state LIMIT 0"))
            try(var s=c.createStatement();var r=s.executeQuery(q)) { /* recognized legacy tables */ }
        if(version>=2) for(String q:List.of("SELECT key,next_at FROM request_cooldowns LIMIT 0","SELECT account_id,email,verified FROM verified_emails LIMIT 0"))
            try(var s=c.createStatement();var r=s.executeQuery(q)) { /* recognized current tables */ }
    }

    public synchronized int schemaVersion() throws SQLException {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT MAX(version) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
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

    // ------------------------------------------------------------------ accounts

    private static final String ACCOUNT_COLS = "id, username, role, owner_id, email, auth_method, password_hash, password_salt, "
            + "password_iterations, key_type, created_at, max_users, quota_mb, group_quota_mb, failed_logins, locked_until, credential_version";

    private static AccountRow account(ResultSet rs) throws SQLException {
        return new AccountRow(rs.getString(1), rs.getString(2), Role.parse(rs.getString(3)), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), intOrNull(rs, 9), rs.getString(10), rs.getLong(11),
                intOrNull(rs, 12), longOrNull(rs, 13), longOrNull(rs, 14), rs.getInt(15), longOrNull(rs, 16), rs.getInt(17));
    }

    public synchronized void insertAccount(AccountRow a) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement("INSERT INTO accounts(" + ACCOUNT_COLS + ", username_lc) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            p.setString(1, a.id());
            p.setString(2, a.username());
            p.setString(3, a.role().wire());
            p.setString(4, a.ownerId());
            p.setString(5, a.email());
            p.setString(6, a.authMethod());
            p.setString(7, a.passwordHash());
            p.setString(8, a.passwordSalt());
            setInt(p, 9, a.passwordIterations());
            p.setString(10, a.keyType());
            p.setLong(11, a.createdAt());
            setInt(p, 12, a.maxUsers());
            setLong(p, 13, a.quotaMb());
            setLong(p, 14, a.groupQuotaMb());
            p.setInt(15, a.failedLogins());
            setLong(p, 16, a.lockedUntil());
            p.setInt(17, a.credentialVersion());
            p.setString(18, a.username().toLowerCase(java.util.Locale.ROOT));
            p.executeUpdate();
        }
    }

    public synchronized Optional<AccountRow> accountById(String id) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement("SELECT " + ACCOUNT_COLS + " FROM accounts WHERE id=?")) {
            p.setString(1, id);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? Optional.of(account(rs)) : Optional.empty();
            }
        }
    }

    public synchronized Optional<AccountRow> accountByUsername(String username) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement("SELECT " + ACCOUNT_COLS + " FROM accounts WHERE username_lc=?")) {
            p.setString(1, username.toLowerCase(java.util.Locale.ROOT));
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? Optional.of(account(rs)) : Optional.empty();
            }
        }
    }

    public synchronized List<AccountRow> accountsByEmail(String email) throws SQLException {
        List<AccountRow> out = new ArrayList<>();
        try (PreparedStatement p = conn.prepareStatement("SELECT " + ACCOUNT_COLS + " FROM accounts WHERE lower(email)=lower(?)")) {
            p.setString(1, email);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) out.add(account(rs));
            }
        }
        return out;
    }

    public synchronized Optional<AccountRow> root() throws SQLException {
        try (PreparedStatement p = conn.prepareStatement("SELECT " + ACCOUNT_COLS + " FROM accounts WHERE role='root'")) {
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? Optional.of(account(rs)) : Optional.empty();
            }
        }
    }

    public synchronized List<AccountRow> allAccounts() throws SQLException {
        List<AccountRow> out = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + ACCOUNT_COLS + " FROM accounts ORDER BY username_lc")) {
            while (rs.next()) out.add(account(rs));
        }
        return out;
    }

    public synchronized List<AccountRow> accountsOwnedBy(String ownerId) throws SQLException {
        List<AccountRow> out = new ArrayList<>();
        try (PreparedStatement p = conn.prepareStatement("SELECT " + ACCOUNT_COLS + " FROM accounts WHERE owner_id=? ORDER BY username_lc")) {
            p.setString(1, ownerId);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) out.add(account(rs));
            }
        }
        return out;
    }

    public synchronized int countOwnedBy(String ownerId) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement("SELECT COUNT(*) FROM accounts WHERE owner_id=?")) {
            p.setString(1, ownerId);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Sets a password credential (clears any key) and bumps the credential version. */
    public synchronized void setPassword(String id, String hash, String salt, int iterations) throws SQLException {
        exec("UPDATE accounts SET auth_method='password', password_hash=?, password_salt=?, password_iterations=?, key_type=NULL, "
                + "credential_version=credential_version+1, failed_logins=0, locked_until=NULL WHERE id=?", hash, salt, iterations, id);
    }

    /** Sets a key credential (clears any password) and bumps the credential version. */
    public synchronized void setKey(String id, String keyType) throws SQLException {
        exec("UPDATE accounts SET auth_method='key', password_hash=NULL, password_salt=NULL, password_iterations=NULL, key_type=?, "
                + "credential_version=credential_version+1, failed_logins=0, locked_until=NULL WHERE id=?", keyType, id);
    }

    public synchronized void setEmail(String id, String email) throws SQLException {
        exec("UPDATE accounts SET email=? WHERE id=?", email, id);
    }

    public synchronized void setQuota(String id, Long quotaMb) throws SQLException {
        exec("UPDATE accounts SET quota_mb=? WHERE id=?", quotaMb, id);
    }

    public synchronized void setGroupQuota(String id, Long groupQuotaMb) throws SQLException {
        exec("UPDATE accounts SET group_quota_mb=? WHERE id=?", groupQuotaMb, id);
    }

    public synchronized void setMaxUsers(String id, Integer maxUsers) throws SQLException {
        exec("UPDATE accounts SET max_users=? WHERE id=?", maxUsers, id);
    }

    public synchronized void recordLoginFailure(String id, int maxFailures, long lockMillis) throws SQLException {
        exec("UPDATE accounts SET failed_logins=failed_logins+1 WHERE id=?", id);
        exec("UPDATE accounts SET locked_until=?, failed_logins=0 WHERE id=? AND failed_logins>=?",
                System.currentTimeMillis() + lockMillis, id, maxFailures);
    }

    public synchronized void resetLoginFailures(String id) throws SQLException {
        exec("UPDATE accounts SET failed_logins=0, locked_until=NULL WHERE id=?", id);
    }

    /** Deletes an account; users it owned move to {@code newOwnerId}. Also removes its invites, reset codes, state. */
    public synchronized void deleteAccount(String id, String newOwnerId) throws SQLException {
        conn.setAutoCommit(false);
        try {
            exec("UPDATE accounts SET owner_id=? WHERE owner_id=?", newOwnerId, id);
            exec("DELETE FROM invites WHERE owner_id=?", id);
            exec("DELETE FROM reset_codes WHERE account_id=?", id);
            exec("DELETE FROM profile_state WHERE account_id=?", id);
            exec("DELETE FROM accounts WHERE id=?", id);
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    // ------------------------------------------------------------------ invites

    private static InviteRow invite(ResultSet rs) throws SQLException {
        return new InviteRow(rs.getString(1), rs.getString(2), Role.parse(rs.getString(3)), rs.getString(4),
                intOrNull(rs, 5), longOrNull(rs, 6), rs.getLong(7));
    }

    private static final String INVITE_COLS = "id, secret_hash, role, owner_id, uses_left, expires_at, created_at";

    public synchronized void insertInvite(InviteRow i) throws SQLException {
        exec("INSERT INTO invites(" + INVITE_COLS + ") VALUES (?,?,?,?,?,?,?)", i.id(), i.secretHash(), i.role().wire(),
                i.ownerId(), i.usesLeft(), i.expiresAt(), i.createdAt());
    }

    public synchronized void deleteExpiredInvites() throws SQLException {
        exec("DELETE FROM invites WHERE (expires_at IS NOT NULL AND expires_at<?) OR (uses_left IS NOT NULL AND uses_left<=0)",
                System.currentTimeMillis());
    }

    public synchronized List<InviteRow> invites(String ownerIdOrNull) throws SQLException {
        deleteExpiredInvites();
        List<InviteRow> out = new ArrayList<>();
        String sql = "SELECT " + INVITE_COLS + " FROM invites" + (ownerIdOrNull == null ? "" : " WHERE owner_id=?") + " ORDER BY created_at";
        try (PreparedStatement p = conn.prepareStatement(sql)) {
            if (ownerIdOrNull != null) p.setString(1, ownerIdOrNull);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) out.add(invite(rs));
            }
        }
        return out;
    }

    public synchronized Optional<InviteRow> inviteById(String id) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement("SELECT " + INVITE_COLS + " FROM invites WHERE id=?")) {
            p.setString(1, id);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? Optional.of(invite(rs)) : Optional.empty();
            }
        }
    }

    public synchronized Optional<InviteRow> inviteBySecretHash(String hash) throws SQLException {
        deleteExpiredInvites();
        try (PreparedStatement p = conn.prepareStatement("SELECT " + INVITE_COLS + " FROM invites WHERE secret_hash=?")) {
            p.setString(1, hash);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? Optional.of(invite(rs)) : Optional.empty();
            }
        }
    }

    public synchronized void deleteInvite(String id) throws SQLException {
        exec("DELETE FROM invites WHERE id=?", id);
    }

    /** Decrements uses (if limited) and deletes the invite when exhausted. */
    public synchronized void consumeInvite(InviteRow i) throws SQLException {
        if (i.usesLeft() == null) return;
        if (i.usesLeft() <= 1) deleteInvite(i.id());
        else exec("UPDATE invites SET uses_left=uses_left-1 WHERE id=?", i.id());
    }

    /** Sum of remaining uses on limited, unexpired invites owned by {@code ownerId}; -1 if any is unlimited. */
    public synchronized int pendingInviteSlots(String ownerId) throws SQLException {
        int total = 0;
        for (InviteRow i : invites(ownerId)) {
            if (i.usesLeft() == null) return -1;
            total += i.usesLeft();
        }
        return total;
    }

    // ------------------------------------------------------------------ reset codes

    public record ResetCode(String accountId, String codeHash, long expiresAt, int attemptsLeft) {}

    public synchronized void putResetCode(String accountId, String codeHash, long expiresAt, int attempts) throws SQLException {
        exec("INSERT OR REPLACE INTO reset_codes(account_id, code_hash, expires_at, attempts_left) VALUES (?,?,?,?)",
                accountId, codeHash, expiresAt, attempts);
    }

    public synchronized Optional<ResetCode> resetCode(String accountId) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement("SELECT account_id, code_hash, expires_at, attempts_left FROM reset_codes WHERE account_id=?")) {
            p.setString(1, accountId);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? Optional.of(new ResetCode(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getInt(4))) : Optional.empty();
            }
        }
    }

    public synchronized void decrementResetAttempts(String accountId) throws SQLException {
        exec("UPDATE reset_codes SET attempts_left=attempts_left-1 WHERE account_id=?", accountId);
        exec("DELETE FROM reset_codes WHERE account_id=? AND attempts_left<=0", accountId);
    }

    public synchronized void deleteResetCode(String accountId) throws SQLException {
        exec("DELETE FROM reset_codes WHERE account_id=?", accountId);
    }

    // ------------------------------------------------------------------ IP failures

    /** Returns the time until which this IP is blocked, or 0. */
    public synchronized long ipBlockedUntil(String ip) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement("SELECT blocked_until FROM ip_failures WHERE ip=?")) {
            p.setString(1, ip);
            try (ResultSet rs = p.executeQuery()) {
                if (rs.next()) {
                    long b = rs.getLong(1);
                    return rs.wasNull() || b < System.currentTimeMillis() ? 0 : b;
                }
            }
        }
        return 0;
    }

    /** Counts a failure in a 10-minute window; blocks the IP when the limit is exceeded. */
    public synchronized void recordIpFailure(String ip, int limit, long blockMillis) throws SQLException {
        long now = System.currentTimeMillis();
        long window = 10 * 60_000L;
        long start = now;
        int count = 0;
        try (PreparedStatement p = conn.prepareStatement("SELECT window_start, count FROM ip_failures WHERE ip=?")) {
            p.setString(1, ip);
            try (ResultSet rs = p.executeQuery()) {
                if (rs.next() && now - rs.getLong(1) < window) {
                    start = rs.getLong(1);
                    count = rs.getInt(2);
                }
            }
        }
        count++;
        Long blocked = count > limit ? now + blockMillis : null;
        exec("INSERT OR REPLACE INTO ip_failures(ip, window_start, count, blocked_until) VALUES (?,?,?,?)", ip, start, count, blocked);
    }

    public synchronized void pruneIpFailures() throws SQLException {
        long now = System.currentTimeMillis();
        exec("DELETE FROM request_cooldowns WHERE next_at<?",now-86_400_000L);
        exec("DELETE FROM ip_failures WHERE window_start<? AND (blocked_until IS NULL OR blocked_until<?)", now - 3_600_000L, now);
    }

    // ------------------------------------------------------------------ profile state

    public synchronized Optional<ProfileStateRow> profileState(String accountId, String profileId) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement("SELECT account_id, profile_id, fingerprint, stored_bytes, last_push_at, "
                + "last_push_device, last_push_by_account FROM profile_state WHERE account_id=? AND profile_id=?")) {
            p.setString(1, accountId);
            p.setString(2, profileId);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new ProfileStateRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        longOrNull(rs, 5), rs.getString(6), rs.getString(7)));
            }
        }
    }

    public synchronized void putProfileState(ProfileStateRow r) throws SQLException {
        exec("INSERT OR REPLACE INTO profile_state(account_id, profile_id, fingerprint, stored_bytes, last_push_at, last_push_device, "
                + "last_push_by_account) VALUES (?,?,?,?,?,?,?)", r.accountId(), r.profileId(), r.fingerprint(), r.storedBytes(),
                r.lastPushAt(), r.lastPushDevice(), r.lastPushByAccount());
    }

    /** Total stored bytes for an account across profiles. */
    public synchronized long storedBytes(String accountId) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement("SELECT COALESCE(SUM(stored_bytes),0) FROM profile_state WHERE account_id=?")) {
            p.setString(1, accountId);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** Most recent push across profiles: [at, device] or null. */
    public synchronized ProfileStateRow lastPush(String accountId) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement("SELECT account_id, profile_id, fingerprint, stored_bytes, last_push_at, "
                + "last_push_device, last_push_by_account FROM profile_state WHERE account_id=? AND last_push_at IS NOT NULL "
                + "ORDER BY last_push_at DESC LIMIT 1")) {
            p.setString(1, accountId);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return null;
                return new ProfileStateRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        longOrNull(rs, 5), rs.getString(6), rs.getString(7));
            }
        }
    }

    public synchronized boolean claimCooldown(String key,long millis) throws SQLException {
        long now=System.currentTimeMillis();
        try(var p=conn.prepareStatement("INSERT INTO request_cooldowns(key,next_at) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET next_at=excluded.next_at WHERE next_at<=?")) {
            p.setString(1,key);p.setLong(2,now+millis);p.setLong(3,now);return p.executeUpdate()==1;
        }
    }
    public synchronized void setEmailToken(String id,String email,String hash,long expiry) throws SQLException {
        exec("INSERT OR REPLACE INTO verified_emails(account_id,email,token_hash,expires_at,verified) VALUES (?,?,?,?,0)",id,email,hash,expiry);
    }
    public synchronized boolean confirmEmail(String id,String email,String hash) throws SQLException {
        try(var p=conn.prepareStatement("UPDATE verified_emails SET verified=1,token_hash=NULL WHERE account_id=? AND email=? AND token_hash=? AND expires_at>?")) {
            p.setString(1,id);p.setString(2,email);p.setString(3,hash);p.setLong(4,System.currentTimeMillis());return p.executeUpdate()==1;
        }
    }
    public synchronized boolean emailVerified(String id,String email) throws SQLException {
        try(var p=conn.prepareStatement("SELECT 1 FROM verified_emails WHERE account_id=? AND email=? AND verified=1")) {
            p.setString(1,id);p.setString(2,email);try(var r=p.executeQuery()) { return r.next(); }
        }
    }
    public synchronized void checkpoint(Path target) throws SQLException,IOException {
        Files.createDirectories(target.getParent());
        try(var stmt=conn.createStatement()) { stmt.execute("VACUUM INTO '"+target.toAbsolutePath().toString().replace("'","''")+"'"); }
    }
    public synchronized void integrity() throws SQLException {
        try(var s=conn.createStatement();var r=s.executeQuery("PRAGMA integrity_check")) {
            if(!r.next() || !"ok".equals(r.getString(1))) throw new SQLException("Database integrity check failed");
        }
    }
    @FunctionalInterface public interface Transaction<T> { T run() throws Exception; }
    public synchronized <T> T transaction(Transaction<T> action) throws Exception {
        conn.setAutoCommit(false);
        try { T result=action.run();conn.commit();return result; }
        catch(Exception e) { conn.rollback();throw e; }
        finally { conn.setAutoCommit(true); }
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

    private static Integer intOrNull(ResultSet rs, int i) throws SQLException {
        int v = rs.getInt(i);
        return rs.wasNull() ? null : v;
    }

    private static Long longOrNull(ResultSet rs, int i) throws SQLException {
        long v = rs.getLong(i);
        return rs.wasNull() ? null : v;
    }

    private static void setInt(PreparedStatement p, int i, Integer v) throws SQLException {
        if (v == null) p.setNull(i, Types.INTEGER);
        else p.setInt(i, v);
    }

    private static void setLong(PreparedStatement p, int i, Long v) throws SQLException {
        if (v == null) p.setNull(i, Types.INTEGER);
        else p.setLong(i, v);
    }
}
