package io.github.azotorp.wowuisync.common.codec;

/** Compression category by file type (DESIGN.md §11.5). */
public enum CompressionCategory {
    /** Already compressed: raw in transit and at rest. */
    ALREADY_COMPRESSED,
    /** Text: compressed in transit, zstd at rest. */
    TEXT,
    /** Compressible binary: compressed in transit, zstd at rest. */
    COMPRESSIBLE_BINARY,
    /** Unsure: decided by a sample test. */
    UNSURE
}
