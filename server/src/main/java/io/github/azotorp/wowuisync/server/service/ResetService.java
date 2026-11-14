package io.github.azotorp.wowuisync.server.service;

import io.github.azotorp.wowuisync.common.Version;
import io.github.azotorp.wowuisync.common.crypto.Passwords;
import io.github.azotorp.wowuisync.common.hash.Hashing;
import io.github.azotorp.wowuisync.common.protocol.ErrorCodes;
import io.github.azotorp.wowuisync.common.protocol.Msg;
import io.github.azotorp.wowuisync.server.db.AccountRow;
import io.github.azotorp.wowuisync.server.db.ServerDb;
import io.github.azotorp.wowuisync.server.net.ApiException;
import io.github.azotorp.wowuisync.server.net.ServerContext;
import io.github.azotorp.wowuisync.server.util.Logs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Email password reset (DESIGN.md §7). Responses never reveal whether an account or email exists. */
public final class ResetService {
    private static final long CODE_MINUTES = 15;
    private static final int ATTEMPTS = 5;

    private final ServerContext ctx;
    private final LoginGuard guard;

    public ResetService(ServerContext ctx, LoginGuard guard) {
        this.ctx = ctx;
        this.guard = guard;
    }

    public Msg.Ok request(String ip, Msg.ResetRequest m) throws ApiException, SQLException {
        if (!ctx.smtp.enabled()) throw new ApiException(ErrorCodes.UNAVAILABLE, "Password reset by email is not set up on this server. Ask the server's owner.");
        guard.checkIp(ip);
        String generic = "If that account exists and has an email address, a code was sent. It is valid for " + CODE_MINUTES + " minutes.";
        String q = m.username() == null ? "" : m.username().trim();
        String rate=Hashing.sha256Hex(q.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        if(!ctx.db.claimCooldown("reset-ip:"+ip,60_000) || !ctx.db.claimCooldown("reset-query:"+rate,120_000)) return new Msg.Ok(generic);
        List<AccountRow> targets = new ArrayList<>();
        ctx.db.accountByUsername(q).ifPresent(targets::add);
        if (targets.isEmpty() && q.contains("@")) targets.addAll(ctx.db.accountsByEmail(q));
        for (AccountRow a : targets) {
            if (a.email() == null || a.usesKey() || !ctx.db.emailVerified(a.id(),a.email()) || !ctx.db.claimCooldown("reset:"+a.id(),120_000)) continue;
            String code = String.format("%06d", Passwords.RANDOM.nextInt(1_000_000));
            ctx.db.putResetCode(a.id(), hash(a.id(), code), System.currentTimeMillis() + CODE_MINUTES * 60_000, ATTEMPTS);
            String to = a.email();
            String user = a.username();
            try { ctx.mailQueue.execute(() -> {
                try {
                    ctx.smtp.send(to, Version.APP_NAME + " password reset code",
                            "Your " + Version.APP_NAME + " password reset code for account '" + user + "' is:\n\n    " + code
                                    + "\n\nIt is valid for " + CODE_MINUTES + " minutes. If you did not ask for this, ignore this email.\n");
                    Logs.LOG.info("Reset code sent for " + user);
                } catch (Exception e) {
                    Logs.LOG.warning("Could not send reset email for " + user + ": " + e.getMessage());
                }
            }); } catch(java.util.concurrent.RejectedExecutionException full) { Logs.LOG.warning("Mail queue full"); }
        }
        return new Msg.Ok(generic);
    }

    public Msg.Ok confirm(String ip, Msg.ResetConfirm m) throws ApiException, SQLException {
        guard.checkIp(ip);
        String bad = "That code is not valid or has expired.";
        AccountRow a = m.username() == null ? null : ctx.db.accountByUsername(m.username().trim()).orElse(null);
        if (a == null && m.username() != null && m.username().contains("@")) {
            List<AccountRow> l = ctx.db.accountsByEmail(m.username().trim());
            if (l.size() == 1) a = l.get(0);
        }
        ServerDb.ResetCode rc = a == null ? null : ctx.db.resetCode(a.id()).orElse(null);
        if (rc == null || rc.expiresAt() < System.currentTimeMillis() || m.code() == null) {
            guard.ipFailure(ip);
            if (rc != null) ctx.db.deleteResetCode(a.id());
            throw new ApiException(ErrorCodes.INVALID, bad);
        }
        if (!MessageDigest.isEqual(rc.codeHash().getBytes(StandardCharsets.US_ASCII),
                hash(a.id(), m.code().trim()).getBytes(StandardCharsets.US_ASCII))) {
            guard.ipFailure(ip);
            ctx.db.decrementResetAttempts(a.id());
            throw new ApiException(ErrorCodes.INVALID, bad);
        }
        String pm = Passwords.validationMessage(m.newPassword() == null ? null : m.newPassword().toCharArray());
        if (pm != null) throw new ApiException(ErrorCodes.INVALID, pm);
        Passwords.Hash h = Passwords.hash(m.newPassword().toCharArray());
        ctx.db.setPassword(a.id(), h.hashB64(), h.saltB64(), h.iterations());
        ctx.db.deleteResetCode(a.id());
        ctx.sessions.endForAccount(a.id(), null);
        Logs.LOG.info("Password reset by email for " + a.username());
        return new Msg.Ok("Your password was changed. You can log in now.");
    }

    public Msg.Ok verificationRequest(AccountRow a) throws Exception {
        if(!ctx.smtp.enabled()) throw new ApiException(ErrorCodes.UNAVAILABLE,"Email delivery is unavailable");
        if(a.email()==null) throw new ApiException(ErrorCodes.INVALID,"Set an email address first");
        if(!ctx.db.claimCooldown("verify:"+a.id(),120_000)) return new Msg.Ok("Wait two minutes before requesting another code");
        String token=Passwords.randomToken(24);
        ctx.db.setEmailToken(a.id(),a.email(),hash(a.id(),token),System.currentTimeMillis()+900_000);
        try { ctx.mailQueue.execute(()-> {
            try { ctx.smtp.send(a.email(),Version.APP_NAME+" verify email","Your verification code is:\n\n"+token+"\n\nExpires in 15 minutes."); }
            catch(Exception e) { Logs.LOG.warning("Verification delivery failed"); }
        }); } catch(java.util.concurrent.RejectedExecutionException e) { throw new ApiException(ErrorCodes.BUSY,"Mail queue is full"); }
        return new Msg.Ok("Verification code sent. Enter it to enable password recovery.");
    }
    public Msg.Ok verificationConfirm(AccountRow a,Msg.EmailVerifyConfirm m) throws Exception {
        if(!ctx.db.claimCooldown("verify-attempt:"+a.id(),3000)) throw new ApiException(ErrorCodes.BUSY,"Wait before trying again");
        if(m.token()==null || !ctx.db.confirmEmail(a.id(),a.email(),hash(a.id(),m.token().trim()))) throw new ApiException(ErrorCodes.INVALID,"Code is invalid or expired");
        Logs.LOG.info("Recovery email verified for "+a.username());return new Msg.Ok("Recovery email verified");
    }

    private static String hash(String accountId, String code) {
        return Hashing.sha256Hex((accountId + ":" + code).getBytes(StandardCharsets.UTF_8));
    }
}
