package io.github.azotorp.wowuisync.server.service;

import io.github.azotorp.wowuisync.common.Role;
import io.github.azotorp.wowuisync.common.crypto.Passwords;
import io.github.azotorp.wowuisync.common.crypto.SshPublicKeys;
import io.github.azotorp.wowuisync.common.protocol.ErrorCodes;
import io.github.azotorp.wowuisync.common.protocol.Msg;
import io.github.azotorp.wowuisync.common.util.Usernames;
import io.github.azotorp.wowuisync.server.db.AccountRow;
import io.github.azotorp.wowuisync.server.db.ProfileStateRow;
import io.github.azotorp.wowuisync.server.net.ApiException;
import io.github.azotorp.wowuisync.server.net.ServerContext;
import io.github.azotorp.wowuisync.server.net.Session;
import io.github.azotorp.wowuisync.server.sync.Storage;
import io.github.azotorp.wowuisync.server.util.Logs;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Account messages (DESIGN.md §6, §10.2). */
public final class AccountService {
    private final ServerContext ctx;
    private final Storage storage;

    public AccountService(ServerContext ctx, Storage storage) {
        this.ctx = ctx;
        this.storage = storage;
    }

    public Msg.AccountInfo info(AccountRow a) throws SQLException {
        String ownerName = null;
        if (a.ownerId() != null) ownerName = ctx.db.accountById(a.ownerId()).map(AccountRow::username).orElse(null);
        ProfileStateRow last = ctx.db.lastPush(a.id());
        return new Msg.AccountInfo(a.id(), a.username(), a.role().wire(), a.ownerId(), ownerName, a.email(), a.authMethod(),
                a.keyType(), a.quotaMb(), a.groupQuotaMb(), a.maxUsers(), ctx.db.storedBytes(a.id()),
                last == null ? null : last.lastPushAt(), last == null ? null : last.lastPushDevice(), a.createdAt());
    }

    public List<Msg.AccountInfo> accessibleInfos(AccountRow actor) throws SQLException {
        List<Msg.AccountInfo> out = new ArrayList<>();
        for (AccountRow a : Access.accessible(ctx.db, actor)) out.add(info(a));
        return out;
    }

    public Msg.AccountListResp list(AccountRow actor) throws SQLException {
        return new Msg.AccountListResp(accessibleInfos(actor));
    }

    /** Validates a username for a new account. */
    void checkNewUsername(String username) throws ApiException, SQLException {
        String m = Usernames.validationMessage(username);
        if (m != null) throw new ApiException(ErrorCodes.INVALID, m);
        if (Usernames.isRoot(username)) throw new ApiException(ErrorCodes.INVALID, "The username 'root' is reserved.");
        if (ctx.db.accountByUsername(username).isPresent()) throw new ApiException(ErrorCodes.CONFLICT, "That username is taken.");
    }

    /** Creates an account with exactly one credential. Writes the key file for key accounts. */
    AccountRow createAccount(String username, Role role, String ownerId, String email, String password, String publicKey,
                             Long quotaMb, Integer maxUsers, Long groupQuotaMb) throws ApiException, SQLException, IOException {
        checkNewUsername(username);
        boolean hasPw = password != null && !password.isEmpty();
        boolean hasKey = publicKey != null && !publicKey.isBlank();
        if (hasPw == hasKey) throw new ApiException(ErrorCodes.INVALID, "Give either a public key or a password.");
        String id = UUID.randomUUID().toString();
        AccountRow row;
        String mail = email == null || email.isBlank() ? null : email.trim();
        if (mail != null && !looksLikeEmail(mail)) throw new ApiException(ErrorCodes.INVALID, "That email address does not look right.");
        if (hasKey) {
            SshPublicKeys.ParsedKey k = parseKey(publicKey);
            Files.createDirectories(ctx.layout.keys());
            Files.writeString(ctx.layout.keyFile(id), k.line() + "\n");
            row = new AccountRow(id, username, role, ownerId, mail, AccountRow.AUTH_KEY, null, null, null, k.type(),
                    System.currentTimeMillis(), maxUsers, quotaMb, groupQuotaMb, 0, null, 0);
        } else {
            String pm = Passwords.validationMessage(password.toCharArray());
            if (pm != null) throw new ApiException(ErrorCodes.INVALID, pm);
            Passwords.Hash h = Passwords.hash(password.toCharArray());
            row = new AccountRow(id, username, role, ownerId, mail, AccountRow.AUTH_PASSWORD, h.hashB64(), h.saltB64(),
                    h.iterations(), null, System.currentTimeMillis(), maxUsers, quotaMb, groupQuotaMb, 0, null, 0);
        }
        try {
            ctx.db.insertAccount(row);
        } catch (SQLException e) {
            Files.deleteIfExists(ctx.layout.keyFile(id));
            if (ctx.db.accountByUsername(username).isPresent()) throw new ApiException(ErrorCodes.CONFLICT, "That username is taken.");
            throw e;
        }
        Logs.LOG.info("Account created: " + username + " (" + role.wire() + ")");
        return row;
    }

    static SshPublicKeys.ParsedKey parseKey(String line) throws ApiException {
        try {
            return SshPublicKeys.parse(line);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCodes.INVALID, e.getMessage());
        }
    }

    static boolean looksLikeEmail(String s) {
        return s.length() <= 254 && s.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    }

    /** Throws if a capped superuser has no room for {@code extra} more users. */
    void checkCap(AccountRow owner, int extra, boolean unlimitedInvite) throws ApiException, SQLException {
        if (owner.role() != Role.SUPERUSER || owner.maxUsers() == null) return;
        if (unlimitedInvite) throw new ApiException(ErrorCodes.QUOTA, "Your account can create at most " + owner.maxUsers()
                + " users, so invites must have a limited number of uses.");
        int pending = ctx.db.pendingInviteSlots(owner.id());
        int used = ctx.db.countOwnedBy(owner.id()) + Math.max(0, pending);
        if (used + extra > owner.maxUsers()) {
            throw new ApiException(ErrorCodes.QUOTA, "Your account can have at most " + owner.maxUsers() + " users ("
                    + used + " used, counting unused invite slots).");
        }
    }

    public Msg.AccountInfo create(AccountRow actor, Msg.AccountCreate m) throws ApiException, SQLException, IOException {
        Role role;
        try {
            role = Role.parse(m.role());
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCodes.INVALID, "Unknown role.");
        }
        if (!Access.canCreate(actor, role)) throw new ApiException(ErrorCodes.FORBIDDEN, "You cannot create " + role.wire() + " accounts.");
        checkCap(actor, 1, false);
        Long quota = normQuota(m.quotaMb());
        Integer maxUsers = null;
        Long groupQuota = null;
        if (actor.role() == Role.ROOT && role == Role.SUPERUSER) {
            maxUsers = m.maxUsers() == null || m.maxUsers() < 0 ? null : m.maxUsers();
            groupQuota = normQuota(m.groupQuotaMb());
        }
        AccountRow row = createAccount(m.username(), role, actor.id(), m.email(), m.password(), m.publicKey(),
                quota, maxUsers, groupQuota);
        return info(row);
    }

    private static Long normQuota(Long q) {
        return q == null || q < 0 ? null : q;
    }

    public Msg.AccountInfo update(AccountRow actor, Msg.AccountUpdate m) throws ApiException, SQLException {
        AccountRow t = ctx.db.accountById(m.accountId()).orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "No such account."));
        boolean self = actor.id().equals(t.id());
        boolean manage = Access.canManage(actor, t);
        if (!self && !manage) throw new ApiException(ErrorCodes.FORBIDDEN, "You cannot change that account.");
        if (m.email() != null) {
            String e = m.email().trim();
            if (!e.isEmpty() && !looksLikeEmail(e)) throw new ApiException(ErrorCodes.INVALID, "That email address does not look right.");
            ctx.db.setEmail(t.id(), e.isEmpty() ? null : e);
        }
        if (m.quotaMb() != null) {
            if (!manage || (self && actor.role() != Role.ROOT)) throw new ApiException(ErrorCodes.FORBIDDEN, "You cannot change this quota.");
            ctx.db.setQuota(t.id(), normQuota(m.quotaMb()));
        }
        if (m.maxUsers() != null || m.groupQuotaMb() != null) {
            if (actor.role() != Role.ROOT || t.role() != Role.SUPERUSER) {
                throw new ApiException(ErrorCodes.FORBIDDEN, "Only root can set max users and group quotas, and only for superusers.");
            }
            if (m.maxUsers() != null) ctx.db.setMaxUsers(t.id(), m.maxUsers() < 0 ? null : m.maxUsers());
            if (m.groupQuotaMb() != null) ctx.db.setGroupQuota(t.id(), normQuota(m.groupQuotaMb()));
        }
        return info(ctx.db.accountById(t.id()).orElseThrow());
    }

    public Msg.AccountInfo setCredential(AccountRow actor, Session session, Msg.AccountSetCredential m)
            throws ApiException, SQLException, IOException {
        AccountRow t = ctx.db.accountById(m.accountId()).orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "No such account."));
        boolean self = actor.id().equals(t.id());
        if (!self && !Access.canManage(actor, t)) throw new ApiException(ErrorCodes.FORBIDDEN, "You cannot change that account.");
        boolean hasPw = m.password() != null && !m.password().isEmpty();
        boolean hasKey = m.publicKey() != null && !m.publicKey().isBlank();
        if (hasPw == hasKey) throw new ApiException(ErrorCodes.INVALID, "Give either a public key or a password.");
        if (hasKey) {
            SshPublicKeys.ParsedKey k = parseKey(m.publicKey());
            Files.createDirectories(ctx.layout.keys());
            Files.writeString(ctx.layout.keyFile(t.id()), k.line() + "\n");
            ctx.db.setKey(t.id(), k.type());
        } else {
            String pm = Passwords.validationMessage(m.password().toCharArray());
            if (pm != null) throw new ApiException(ErrorCodes.INVALID, pm);
            Passwords.Hash h = Passwords.hash(m.password().toCharArray());
            ctx.db.setPassword(t.id(), h.hashB64(), h.saltB64(), h.iterations());
            Files.deleteIfExists(ctx.layout.keyFile(t.id()));
        }
        AccountRow updated = ctx.db.accountById(t.id()).orElseThrow();
        // Credentials changed: end the account's other sessions; keep the caller's own session alive.
        ctx.sessions.endForAccount(t.id(), self ? session : null);
        if (self) session.refresh(updated.username(), updated.role(), updated.credentialVersion());
        Logs.LOG.info("Credentials changed for " + t.username() + " by " + actor.username());
        return info(updated);
    }

    public void delete(AccountRow actor, Msg.AccountDelete m) throws ApiException, SQLException, IOException {
        AccountRow t = ctx.db.accountById(m.accountId()).orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "No such account."));
        if (t.role() == Role.ROOT) throw new ApiException(ErrorCodes.FORBIDDEN, "The root account cannot be deleted.");
        if (!Access.canManage(actor, t)) throw new ApiException(ErrorCodes.FORBIDDEN, "You cannot delete that account.");
        if (m.confirmUsername() == null || !m.confirmUsername().equals(t.username())) {
            throw new ApiException(ErrorCodes.INVALID, "Type the username exactly to confirm.");
        }
        if (!storage.tryLockAccount(t.id())) throw new ApiException(ErrorCodes.BUSY, "That account has a transfer in progress. Try again later.");
        try {
            String newOwner = ctx.db.root().map(AccountRow::id).orElse(null);
            ctx.sessions.endForAccount(t.id(), null);
            ctx.db.deleteAccount(t.id(), newOwner);
            Files.deleteIfExists(ctx.layout.keyFile(t.id()));
            storage.deleteAccountData(t.id());
        } finally {
            storage.unlockAccount(t.id());
        }
        Logs.LOG.info("Account deleted: " + t.username() + " by " + actor.username());
    }
}
