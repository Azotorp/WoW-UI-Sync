package io.github.azotorp.wowuisync.common.manifest;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One synced file: path (relative, forward slashes, original casing), size in bytes, lower-case hex SHA-256. */
public record ManifestEntry(
        @JsonProperty("p") String path,
        @JsonProperty("s") long size,
        @JsonProperty("h") String sha256) {

    /** Line used for fingerprints: {@code path|size|sha256}. */
    public String fingerprintLine() {
        return path + "|" + size + "|" + sha256;
    }
}
