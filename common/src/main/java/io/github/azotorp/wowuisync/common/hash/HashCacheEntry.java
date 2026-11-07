package io.github.azotorp.wowuisync.common.hash;

/** Cached hash of a local file; re-hash only when size or lastModified change (DESIGN.md §11.1). */
public record HashCacheEntry(String path, long size, long lastModified, String sha256) {
    public boolean matches(long size, long lastModified) {
        return this.size == size && this.lastModified == lastModified;
    }
}
