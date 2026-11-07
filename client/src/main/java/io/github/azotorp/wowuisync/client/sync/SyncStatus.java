package io.github.azotorp.wowuisync.client.sync;

import io.github.azotorp.wowuisync.common.protocol.Msg;

/** Status per (account, profile) on this device (DESIGN.md §11.3). */
public record SyncStatus(Kind kind, LocalScan scan, Msg.StatusResp server, String base) {
    public enum Kind {
        UP_TO_DATE,
        LOCAL_CHANGES,
        SERVER_NEWER,
        CONFLICT,
        DIFFERENT,
        SERVER_EMPTY
    }
}
