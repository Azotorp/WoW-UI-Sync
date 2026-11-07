package io.github.azotorp.wowuisync.server.net;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Registry of live sessions. */
public final class Sessions {
    private final Map<String, Session> byId = new ConcurrentHashMap<>();

    public void add(Session s) {
        byId.put(s.id(), s);
    }

    public Session get(String id) {
        return id == null ? null : byId.get(id);
    }

    public void remove(Session s) {
        byId.remove(s.id(), s);
    }

    /** Ends every session of an account except {@code keep} (may be null). */
    public void endForAccount(String accountId, Session keep) {
        for (Session s : byId.values()) {
            if (s.accountId().equals(accountId) && s != keep) {
                s.close();
                byId.remove(s.id(), s);
            }
        }
    }

    public Collection<Session> all() {
        return byId.values();
    }

    public void closeAll() {
        for (Session s : byId.values()) s.close();
        byId.clear();
    }
}
