package io.github.azotorp.wowuisync.server.service;

import io.github.azotorp.wowuisync.common.crypto.KeyLogin;
import io.github.azotorp.wowuisync.common.crypto.Passwords;
import io.github.azotorp.wowuisync.common.crypto.SshPublicKeys;
import io.github.azotorp.wowuisync.common.protocol.Msg;
import io.github.azotorp.wowuisync.server.db.AccountRow;
import io.github.azotorp.wowuisync.server.net.ServerContext;
import io.github.azotorp.wowuisync.server.util.Logs;

import java.nio.file.Files;
import java.sql.SQLException;
import java.util.Base64;

/** Password and key login with account lockout and IP blocking (DESIGN.md §7). */
public final class AuthService {
    /** Result of a login step: either an authenticated account or a failure message. */
    public record Result(AccountRow account, Msg.LoginFail fail) {
        static Result ok(AccountRow a) {
            return new Result(a, null);
        }

        static Result fail(String reason, String message, Long retry) {
            return new Result(null, new Msg.LoginFail(reason, message, retry, null));
        }
    }

    /** Pending key challenge on one connection. */
    public record Challenge(String username, String nonce, String sessionId) {}

    private static final String BAD = "Wrong username or credentials.";

    private final ServerContext ctx;
    private final LoginGuard guard;

    public AuthService(ServerContext ctx, LoginGuard guard) {
        this.ctx = ctx;
        this.guard = guard;
    }

    private Result precheck(String ip, AccountRow a, boolean wantKey) throws SQLException {
        long blocked = guard.ipBlockedSeconds(ip);
        if (blocked > 0) {
            return Result.fail("ip_blocked", "Too many failed attempts from your address. Try again in " + LoginGuard.minutes(blocked) + ".", blocked);
        }
        if (a == null) return null;
        if (a.lockedUntil() != null && a.lockedUntil() > System.currentTimeMillis()) {
            long s = (a.lockedUntil() - System.currentTimeMillis() + 999) / 1000;
            return Result.fail("locked", "This account is locked after too many failed logins. Try again in " + LoginGuard.minutes(s) + ".", s);
        }
        if (a.usesKey() != wantKey) {
            return Result.fail("wrong_method", a.usesKey()
                    ? "This account logs in with a public key, not a password. Choose a private key file in the connection."
                    : "This account logs in with a password, not a key. Enter the password in the connection.", null);
        }
        return null;
    }

    private Result failure(String ip, AccountRow a) throws SQLException {
        guard.ipFailure(ip);
        if (a != null) {
            ctx.db.recordLoginFailure(a.id(), ctx.config.lockout.accountFailures, ctx.config.lockout.accountLockMinutes * 60_000L);
        }
        Logs.LOG.info("Failed login" + (a != null ? " for " + a.username() : "") + " from " + ip);
        return Result.fail("bad_credentials", BAD, null);
    }

    private Result success(AccountRow a) throws SQLException {
        ctx.db.resetLoginFailures(a.id());
        return Result.ok(ctx.db.accountById(a.id()).orElse(a));
    }

    public Result loginPassword(String ip, Msg.LoginPassword m) throws SQLException {
        AccountRow a = m.username() == null ? null : ctx.db.accountByUsername(m.username()).orElse(null);
        Result pre = precheck(ip, a, false);
        if (pre != null) return pre;
        if (a == null || m.password() == null) {
            // Spend comparable time so response timing does not reveal whether the account exists.
            Passwords.hash(new char[] {'x'});
            return failure(ip, null);
        }
        boolean ok = Passwords.verify(m.password().toCharArray(), a.passwordHash(), a.passwordSalt(), a.passwordIterations());
        return ok ? success(a) : failure(ip, a);
    }

    /** Starts key login. Returns a challenge, or a failure result. Unknown users still get a challenge. */
    public Object beginKey(String ip, Msg.LoginKeyBegin m) throws SQLException {
        AccountRow a = m.username() == null ? null : ctx.db.accountByUsername(m.username()).orElse(null);
        Result pre = precheck(ip, a, true);
        if (pre != null) return pre;
        return new Challenge(m.username(), Base64.getEncoder().encodeToString(Passwords.randomBytes(32)), Passwords.randomToken(32));
    }

    public Result finishKey(String ip, Challenge c, Msg.LoginKeyProof m) throws SQLException {
        AccountRow a = c.username() == null ? null : ctx.db.accountByUsername(c.username()).orElse(null);
        Result pre = precheck(ip, a, true);
        if (pre != null) return pre;
        if (a == null || m.signature() == null) return failure(ip, a);
        try {
            String line = Files.readString(ctx.layout.keyFile(a.id())).trim();
            SshPublicKeys.ParsedKey key = SshPublicKeys.parse(line);
            byte[] data = KeyLogin.signedData(c.nonce(), c.sessionId(), ctx.fingerprint);
            byte[] sig = Base64.getDecoder().decode(m.signature());
            return SshPublicKeys.verify(key, data, sig) ? success(a) : failure(ip, a);
        } catch (Exception e) {
            Logs.LOG.warning("Key login for " + a.username() + " failed: " + e.getMessage());
            return failure(ip, a);
        }
    }
}
