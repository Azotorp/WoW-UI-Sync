package io.github.azotorp.wowuisync.server.service;

import io.github.azotorp.wowuisync.common.Role;
import io.github.azotorp.wowuisync.common.protocol.ErrorCodes;
import io.github.azotorp.wowuisync.server.db.AccountRow;
import io.github.azotorp.wowuisync.server.db.ServerDb;
import io.github.azotorp.wowuisync.server.net.ApiException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Permission rules (DESIGN.md §6), checked on every request. */
public final class Access {
    private Access() {}

    /** Read/write access to an account's data. */
    public static boolean canAccessData(AccountRow actor, AccountRow target) {
        return switch (actor.role()) {
            case ROOT -> true;
            case SUPERUSER -> actor.id().equals(target.id()) || (target.role() == Role.USER && actor.id().equals(target.ownerId()));
            case USER -> actor.id().equals(target.id());
        };
    }

    /** Manage another account (credentials, email, quota, delete). Self-service is separate. */
    public static boolean canManage(AccountRow actor, AccountRow target) {
        return switch (actor.role()) {
            case ROOT -> true;
            case SUPERUSER -> target.role() == Role.USER && actor.id().equals(target.ownerId());
            case USER -> false;
        };
    }

    public static boolean canCreate(AccountRow actor, Role role) {
        return switch (actor.role()) {
            case ROOT -> role == Role.SUPERUSER || role == Role.USER;
            case SUPERUSER -> role == Role.USER;
            case USER -> false;
        };
    }

    public static List<AccountRow> accessible(ServerDb db, AccountRow actor) throws SQLException {
        return switch (actor.role()) {
            case ROOT -> db.allAccounts();
            case SUPERUSER -> {
                List<AccountRow> l = new ArrayList<>();
                l.add(actor);
                for (AccountRow a : db.accountsOwnedBy(actor.id())) if (a.role() == Role.USER) l.add(a);
                yield l;
            }
            case USER -> List.of(actor);
        };
    }

    public static AccountRow requireData(ServerDb db, AccountRow actor, String targetId) throws SQLException, ApiException {
        AccountRow t = targetId == null ? null : db.accountById(targetId).orElse(null);
        if (t == null || !canAccessData(actor, t)) throw new ApiException(ErrorCodes.FORBIDDEN, "You do not have access to that account.");
        return t;
    }
}
