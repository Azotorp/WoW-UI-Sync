package io.github.azotorp.wowuisync.server.db;

import io.github.azotorp.wowuisync.common.Role;

/** A row of {@code accounts}. */
public record AccountRow(String id, String username, Role role, String ownerId, String email, String authMethod,
                         String passwordHash, String passwordSalt, Integer passwordIterations, String keyType,
                         long createdAt, Integer maxUsers, Long quotaMb, Long groupQuotaMb, int failedLogins,
                         Long lockedUntil, int credentialVersion) {
    public static final String AUTH_PASSWORD = "password";
    public static final String AUTH_KEY = "key";

    public boolean usesKey() {
        return AUTH_KEY.equals(authMethod);
    }
}
