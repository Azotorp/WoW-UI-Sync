package io.github.azotorp.wowuisync.common.manifest;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.azotorp.wowuisync.common.chunk.ChunkRef;
import java.util.List;

/** Whole-file integrity plus ordered immutable content references. Empty c = legacy full-file object. */
public record ManifestEntry(@JsonProperty("p") String path, @JsonProperty("s") long size,
                            @JsonProperty("h") String sha256, @JsonProperty("c") List<ChunkRef> chunks) {
    public ManifestEntry { chunks = chunks == null ? List.of() : List.copyOf(chunks); }
    public ManifestEntry(String path, long size, String sha256) { this(path, size, sha256, List.of()); }
    public List<ChunkRef> objects() { return chunks.isEmpty() ? List.of(new ChunkRef(sha256, size)) : chunks; }
    // Content fingerprint remains independent of repository chunking version.
    public String fingerprintLine() { return path + "|" + size + "|" + sha256; }
}
