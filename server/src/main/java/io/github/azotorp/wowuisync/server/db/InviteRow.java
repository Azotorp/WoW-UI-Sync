package io.github.azotorp.wowuisync.server.db;

import io.github.azotorp.wowuisync.common.Role;

/** A row of {@code invites}. {@code usesLeft} null = unlimited. */
public record InviteRow(String id, String secretHash, Role role, String ownerId, Integer usesLeft, Long expiresAt, long createdAt) {}
