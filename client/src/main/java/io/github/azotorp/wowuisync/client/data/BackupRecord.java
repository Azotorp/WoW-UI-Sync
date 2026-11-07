package io.github.azotorp.wowuisync.client.data;

/** A local backup pair {@code Interface_<suffix>} / {@code WTF_<suffix>} (DESIGN.md §18). */
public record BackupRecord(String flavorPath, String suffix, String account, long createdAt, String reason,
                           String sourceAccount, long fileCount, long sizeBytes) {}
