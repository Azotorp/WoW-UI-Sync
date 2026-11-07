package io.github.azotorp.wowuisync.server.service;

import io.github.azotorp.wowuisync.common.protocol.ErrorCodes;
import io.github.azotorp.wowuisync.server.net.ApiException;
import io.github.azotorp.wowuisync.server.net.ServerContext;

import java.sql.SQLException;

/** IP blocking (DESIGN.md §7): more than N failures in 10 minutes blocks the IP. */
public final class LoginGuard {
    private final ServerContext ctx;

    public LoginGuard(ServerContext ctx) {
        this.ctx = ctx;
    }

    /** Seconds remaining on an IP block, or 0. */
    public long ipBlockedSeconds(String ip) throws SQLException {
        long until = ctx.db.ipBlockedUntil(ip);
        return until == 0 ? 0 : Math.max(1, (until - System.currentTimeMillis() + 999) / 1000);
    }

    public void checkIp(String ip) throws ApiException, SQLException {
        long s = ipBlockedSeconds(ip);
        if (s > 0) throw new ApiException(ErrorCodes.IP_BLOCKED, "Too many failed attempts from your address. Try again in "
                + minutes(s) + ".");
    }

    public void ipFailure(String ip) throws SQLException {
        ctx.db.recordIpFailure(ip, ctx.config.lockout.ipFailuresPer10Min, ctx.config.lockout.ipBlockMinutes * 60_000L);
    }

    public static String minutes(long seconds) {
        long m = (seconds + 59) / 60;
        return m <= 1 ? "1 minute" : m + " minutes";
    }
}
