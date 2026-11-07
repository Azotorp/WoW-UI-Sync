package io.github.azotorp.wowuisync.common.codec;

import io.github.azotorp.wowuisync.common.path.SyncPaths;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Decides whether a file is compressed in transit and at rest (DESIGN.md §11.5). */
public final class CompressionPolicy {
    public static final int SAMPLE_BYTES = 256 * 1024;
    /** Minimum saving for the sample test to call a file compressible. */
    public static final double MIN_SAVING = 0.05;

    private static final Set<String> ALREADY = Set.of(
            ".ogg", ".mp3", ".m4a", ".aac", ".flac", ".opus", ".jpg", ".jpeg", ".png", ".zip", ".gz", ".7z", ".rar");
    private static final Set<String> TEXT = Set.of(
            ".lua", ".toc", ".xml", ".txt", ".md", ".html", ".css", ".json", ".wtf", ".textile", ".pkgmeta",
            ".md5", ".log", ".bak", ".old", "");
    private static final Set<String> BINARY = Set.of(".tga", ".ttf", ".otf", ".wav", ".m2");

    /** Sample-test results by SHA-256 (true = compressible). */
    private final Map<String, Boolean> sampleCache = new ConcurrentHashMap<>();

    public static CompressionCategory categoryOf(String path) {
        String ext = SyncPaths.extension(path);
        if (ALREADY.contains(ext)) return CompressionCategory.ALREADY_COMPRESSED;
        if (TEXT.contains(ext)) return CompressionCategory.TEXT;
        if (BINARY.contains(ext)) return CompressionCategory.COMPRESSIBLE_BINARY;
        return CompressionCategory.UNSURE;
    }

    /** Sample test: compress the first 256 KB at level 1; under 5% saving → not compressible. */
    public static boolean sampleTest(byte[] sample, int len) {
        if (len <= 0) return false;
        int n = Math.min(len, SAMPLE_BYTES);
        byte[] c = Codec.preferred().compress(sample, 0, n, 1);
        return c.length < n * (1.0 - MIN_SAVING);
    }

    public Boolean cached(String sha256) {
        return sampleCache.get(sha256);
    }

    public void remember(String sha256, boolean compressible) {
        sampleCache.put(sha256, compressible);
    }

    public Map<String, Boolean> cacheView() {
        return sampleCache;
    }

    /**
     * Whether to compress. For UNSURE files, {@code sampleSupplier} is called once per hash (result cached).
     */
    public boolean shouldCompress(String path, String sha256, SampleSupplier sampleSupplier) {
        CompressionCategory cat = categoryOf(path);
        switch (cat) {
            case ALREADY_COMPRESSED:
                return false;
            case TEXT:
            case COMPRESSIBLE_BINARY:
                return true;
            default:
                Boolean b = sampleCache.get(sha256);
                if (b != null) return b;
                boolean r;
                try {
                    byte[] s = sampleSupplier.sample(SAMPLE_BYTES);
                    r = s != null && sampleTest(s, s.length);
                } catch (Exception e) {
                    r = true;
                }
                sampleCache.put(sha256, r);
                return r;
        }
    }

    @FunctionalInterface
    public interface SampleSupplier {
        byte[] sample(int maxBytes) throws Exception;
    }
}
