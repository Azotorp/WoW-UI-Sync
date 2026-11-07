package io.github.azotorp.wowuisync.server.service;

import io.github.azotorp.wowuisync.common.Role;
import io.github.azotorp.wowuisync.common.crypto.Passwords;
import io.github.azotorp.wowuisync.common.hash.Hashing;
import io.github.azotorp.wowuisync.common.protocol.ErrorCodes;
import io.github.azotorp.wowuisync.common.protocol.InviteCode;
import io.github.azotorp.wowuisync.common.protocol.Msg;
import io.github.azotorp.wowuisync.server.db.AccountRow;
import io.github.azotorp.wowuisync.server.db.InviteRow;
import io.github.azotorp.wowuisync.server.net.ApiException;
import io.github.azotorp.wowuisync.server.net.ServerContext;
import io.github.azotorp.wowuisync.server.util.Logs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Invite codes (DESIGN.md §8). */
public final class InviteService {
    private final ServerContext ctx;
    private final AccountService accounts;
    private final LoginGuard guard;

    public InviteService(ServerContext ctx, AccountService accounts, LoginGuard guard) {
        this.ctx = ctx;
        this.accounts = accounts;
        this.guard = guard;
    }

    private static String hashSecret(String secret) {
        return Hashing.sha256Hex(secret.getBytes(StandardCharsets.UTF_8));
    }

    private Msg.InviteInfo info(InviteRow i) throws SQLException {
        String owner = ctx.db.accountById(i.ownerId()).map(AccountRow::username).orElse(null);
        return new Msg.InviteInfo(i.id(), i.role().wire(), i.ownerId(), owner, i.usesLeft(), i.expiresAt(), i.createdAt());
    }

    public Msg.InviteCreated create(AccountRow actor, Msg.InviteCreate m) throws ApiException, SQLException {
        Role role;
        try {
            role = Role.parse(m.role());
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCodes.INVALID, "Unknown role.");
        }
        if (!Access.canCreate(actor, role)) throw new ApiException(ErrorCodes.FORBIDDEN, "You cannot invite " + role.wire() + " accounts.");
        Integer uses = m.uses();
        if (uses != null && (uses < 1 || uses > 100_000)) throw new ApiException(ErrorCodes.INVALID, "Uses must be 1 or more.");
        if (m.expiresAt() != null && m.expiresAt() <= System.currentTimeMillis()) {
            throw new ApiException(ErrorCodes.INVALID, "The expiry must be in the future.");
        }
        accounts.checkCap(actor, uses == null ? 0 : uses, uses == null);
        String secret = Passwords.randomToken(24);
        InviteRow row = new InviteRow(UUID.randomUUID().toString(), hashSecret(secret), role, actor.id(), uses, m.expiresAt(),
                System.currentTimeMillis());
        ctx.db.insertInvite(row);
        String code = new InviteCode(ctx.publicHost(), ctx.config.port, ctx.fingerprint, secret).encode();
        Logs.LOG.info("Invite created by " + actor.username() + " for role " + role.wire());
        return new Msg.InviteCreated(code, info(row));
    }

    public Msg.InviteListResp list(AccountRow actor) throws ApiException, SQLException {
        if (actor.role() == Role.USER) throw new ApiException(ErrorCodes.FORBIDDEN, "Users cannot create invites.");
        List<Msg.InviteInfo> out = new ArrayList<>();
        for (InviteRow i : ctx.db.invites(actor.role() == Role.ROOT ? null : actor.id())) out.add(info(i));
        return new Msg.InviteListResp(out);
    }

    public void revoke(AccountRow actor, Msg.InviteRevoke m) throws ApiException, SQLException {
        InviteRow i = ctx.db.inviteById(m.id()).orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "No such invite."));
        if (actor.role() != Role.ROOT && !i.ownerId().equals(actor.id())) throw new ApiException(ErrorCodes.FORBIDDEN, "Not your invite.");
        ctx.db.deleteInvite(i.id());
    }

    /** Pre-login: creates an account from an invite. */
    public Msg.AccountInfo redeem(String ip, Msg.InviteRedeem m) throws ApiException, SQLException, IOException {
        guard.checkIp(ip);
        synchronized (this) {
            InviteRow i = m.secret() == null ? null : ctx.db.inviteBySecretHash(hashSecret(m.secret())).orElse(null);
            if (i == null) {
                guard.ipFailure(ip);
                throw new ApiException(ErrorCodes.INVALID, "This invite code is not valid, has expired, or has been used up.");
            }
            AccountRow owner = ctx.db.accountById(i.ownerId()).orElse(null);
            if (owner == null) {
                ctx.db.deleteInvite(i.id());
                throw new ApiException(ErrorCodes.INVALID, "This invite code is no longer valid.");
            }
            AccountRow row = accounts.createAccount(m.username(), i.role(), owner.id(), m.email(), m.password(), m.publicKey(),
                    null, null, null);
            ctx.db.consumeInvite(i);
            Logs.LOG.info("Invite redeemed: " + row.username() + " (owner " + owner.username() + ")");
            return accounts.info(row);
        }
    }
}
