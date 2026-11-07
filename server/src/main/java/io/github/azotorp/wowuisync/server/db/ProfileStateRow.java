package io.github.azotorp.wowuisync.server.db;

/** A row of {@code profile_state}. */
public record ProfileStateRow(String accountId, String profileId, String fingerprint, long storedBytes,
                              Long lastPushAt, String lastPushDevice, String lastPushByAccount) {}
