package io.github.azotorp.wowuisync.common;

/** Defaults summary (DESIGN.md §20). */
public final class Defaults {
    public static final int PORT = 2026;
    public static final String BIND_ADDRESS = "0.0.0.0";
    public static final String CLIENT_HOST = "localhost";
    public static final int DATA_CONNECTIONS = 6;
    public static final int MAX_DATA_CONNECTIONS = 16;
    public static final long LARGE_FILE_THRESHOLD = 4L << 20;
    public static final int COMPRESSED_PACK_BYTES = 8 << 20;
    public static final int RAW_PACK_BYTES = 16 << 20;
    public static final int CHUNK_BYTES = 1 << 20;
    public static final int ZSTD_LEVEL = 3;
    public static final int MIN_PASSWORD_LENGTH = 8;
    public static final int PBKDF2_ITERATIONS = 600_000;
    public static final long PARTIAL_MAX_AGE_MS = 7L * 24 * 3600 * 1000;
    /** Largest frame payload accepted (packs + headroom). */
    public static final int MAX_FRAME_PAYLOAD = 64 << 20;

    private Defaults() {}
}
