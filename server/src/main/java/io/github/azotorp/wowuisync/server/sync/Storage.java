package io.github.azotorp.wowuisync.server.sync;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.azotorp.wowuisync.common.Defaults;
import io.github.azotorp.wowuisync.common.codec.Codec;
import io.github.azotorp.wowuisync.common.hash.Hashing;
import io.github.azotorp.wowuisync.common.json.Json;
import io.github.azotorp.wowuisync.common.manifest.Manifest;
import io.github.azotorp.wowuisync.common.manifest.ManifestEntry;
import io.github.azotorp.wowuisync.common.manifest.ManifestIO;
import io.github.azotorp.wowuisync.common.object.ObjectFiles;
import io.github.azotorp.wowuisync.server.config.DataLayout;
import io.github.azotorp.wowuisync.server.util.Logs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Per-account, per-profile content-addressed object storage and the current manifest (DESIGN.md §9).
 */
public class Storage {
    /** {@code current.manifest} contents (codec-tagged JSON). */
    public record StoredManifest(@JsonProperty("fingerprint") String fingerprint,
                                 @JsonProperty("entries") List<ManifestEntry> entries,
                                 @JsonProperty("lastPushAt") Long lastPushAt,
                                 @JsonProperty("lastPushDevice") String lastPushDevice,
                                 @JsonProperty("lastPushBy") String lastPushBy) {
        public Manifest manifest() {
            return new Manifest(entries == null ? List.of() : entries);
        }
    }

    protected final DataLayout layout;
    private final Set<String> locked = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> transfers = new ConcurrentHashMap<>();
    private final Map<String, Object> profileLocks = new ConcurrentHashMap<>();

    public Storage(DataLayout layout) {
        this.layout = layout;
    }

    // ------------------------------------------------------------------ locks and transfer tracking

    /** Exclusive lock for deleting an account; fails while transfers are active. */
    public synchronized boolean tryLockAccount(String accountId) {
        for (Map.Entry<String, AtomicInteger> e : transfers.entrySet()) {
            if (e.getKey().startsWith(accountId + "/") && e.getValue().get() > 0) return false;
        }
        return locked.add(accountId);
    }

    public synchronized void unlockAccount(String accountId) {
        locked.remove(accountId);
    }

    /** Marks a transfer as active on (account, profile). Returns false if the account is being deleted. */
    public synchronized boolean beginTransfer(String accountId, String profileId) {
        if (locked.contains(accountId)) return false;
        transfers.computeIfAbsent(accountId + "/" + profileId, k -> new AtomicInteger()).incrementAndGet();
        return true;
    }

    public synchronized void endTransfer(String accountId, String profileId) {
        AtomicInteger c = transfers.get(accountId + "/" + profileId);
        if (c != null && c.decrementAndGet() <= 0) transfers.remove(accountId + "/" + profileId);
    }

    public synchronized int activeTransfers(String accountId, String profileId) {
        AtomicInteger c = transfers.get(accountId + "/" + profileId);
        return c == null ? 0 : c.get();
    }

    /** Monitor for serializing commits on one (account, profile). */
    public Object profileLock(String accountId, String profileId) {
        return profileLocks.computeIfAbsent(accountId + "/" + profileId, k -> new Object());
    }

    // ------------------------------------------------------------------ manifests

    public StoredManifest readManifest(String accountId, String profileId) throws IOException {
        Path p = layout.currentManifest(accountId, profileId);
        if (!Files.exists(p)) return null;
        return Json.fromBytes(Codec.decodeTagged(Files.readAllBytes(p)), StoredManifest.class);
    }

    public void writeManifest(String accountId, String profileId, StoredManifest m) throws IOException {
        Path p = layout.currentManifest(accountId, profileId);
        Files.createDirectories(p.getParent());
        ManifestIO.write(p, Codec.preferred().encodeTagged(Json.toBytes(m)));
    }

    // ------------------------------------------------------------------ objects

    public Path objectPath(String accountId, String profileId, String sha) {
        return ObjectFiles.shardPath(layout.objects(accountId, profileId), sha);
    }

    public boolean hasObject(String accountId, String profileId, String sha) {
        return Files.isRegularFile(objectPath(accountId, profileId, sha));
    }

    /** Stores verified content. {@code compress}: zstd (or deflate) at rest, else raw. */
    public void storeObject(String accountId, String profileId, String sha, byte[] data, boolean compress) throws IOException {
        Path target = objectPath(accountId, profileId, sha);
        if (Files.exists(target)) return;
        Files.createDirectories(layout.tmp());
        Path tmp = layout.tmp().resolve(UUID.randomUUID() + ".obj");
        try {
            ObjectFiles.write(tmp, data, compress ? Codec.preferred() : Codec.RAW, Defaults.ZSTD_LEVEL);
            moveIntoPlace(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Stores a completed partial upload (already verified) as an object. */
    public void storeObjectFromFile(String accountId, String profileId, String sha, Path rawFile, boolean compress) throws IOException {
        Path target = objectPath(accountId, profileId, sha);
        if (Files.exists(target)) return;
        Files.createDirectories(layout.tmp());
        Path tmp = layout.tmp().resolve(UUID.randomUUID() + ".obj");
        try (InputStream in = Files.newInputStream(rawFile)) {
            ObjectFiles.writeStream(tmp, in, Files.size(rawFile), compress ? Codec.preferred() : Codec.RAW, Defaults.ZSTD_LEVEL);
            moveIntoPlace(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void moveIntoPlace(Path tmp, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException e) {
            // another connection stored it first
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(tmp, target);
            } catch (FileAlreadyExistsException ignored) {
                // stored concurrently
            }
        } catch (IOException e) {
            if (!Files.exists(target)) throw e;
        }
    }

    // ------------------------------------------------------------------ partial uploads

    public Path partialPath(String accountId, String sha) {
        return layout.partial().resolve(accountId).resolve(sha);
    }

    public long partialSize(String accountId, String sha) {
        try {
            Path p = partialPath(accountId, sha);
            return Files.exists(p) ? Files.size(p) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /** Appends a chunk at {@code offset}; the partial file must currently be exactly {@code offset} bytes. */
    public long appendPartial(String accountId, String sha, long offset, byte[] buf, int off, int len) throws IOException {
        Path p = partialPath(accountId, sha);
        Files.createDirectories(p.getParent());
        long size = Files.exists(p) ? Files.size(p) : 0;
        if (offset == 0 && size > 0) {
            Files.delete(p);
            size = 0;
        }
        if (size != offset) return size;
        try (OutputStream out = Files.newOutputStream(p, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            out.write(buf, off, len);
        }
        return offset + len;
    }

    public String sha256OfFile(Path p) throws IOException {
        return Hashing.sha256Hex(p);
    }

    public void deletePartial(String accountId, String sha) throws IOException {
        Files.deleteIfExists(partialPath(accountId, sha));
    }

    /** Deletes partial uploads older than 7 days and stray temp objects older than a day. */
    public void cleanPartials() {
        long now = System.currentTimeMillis();
        try {
            if (Files.exists(layout.partial())) {
                try (Stream<Path> s = Files.walk(layout.partial())) {
                    for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                        if (now - Files.getLastModifiedTime(p).toMillis() > Defaults.PARTIAL_MAX_AGE_MS) Files.deleteIfExists(p);
                    }
                }
            }
            if (Files.exists(layout.tmp())) {
                try (Stream<Path> s = Files.list(layout.tmp())) {
                    for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                        if (now - Files.getLastModifiedTime(p).toMillis() > 86_400_000L) Files.deleteIfExists(p);
                    }
                }
            }
        } catch (IOException e) {
            Logs.LOG.warning("Cleaning partial uploads failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ GC and sizes

    /** Deletes objects not referenced by {@code keep}. Returns the total size of remaining object files. */
    public long gc(String accountId, String profileId, Set<String> keep) throws IOException {
        Path root = layout.objects(accountId, profileId);
        if (!Files.exists(root)) return 0;
        long total = 0;
        int deleted = 0;
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile).forEach(files::add);
        }
        for (Path f : files) {
            String name = f.getFileName().toString();
            if (keep.contains(name)) total += Files.size(f);
            else {
                Files.deleteIfExists(f);
                deleted++;
            }
        }
        // Remove empty shard folders.
        try (Stream<Path> s = Files.walk(root)) {
            List<Path> dirs = s.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList();
            for (Path d : dirs) {
                if (d.equals(root)) continue;
                try (Stream<Path> c = Files.list(d)) {
                    if (c.findAny().isEmpty()) Files.deleteIfExists(d);
                }
            }
        }
        if (deleted > 0) Logs.LOG.fine("GC removed " + deleted + " objects for " + accountId + "/" + profileId);
        return total;
    }

    /** Sum of object file sizes for the given hashes (missing ones count as 0). */
    public long objectBytes(String accountId, String profileId, Set<String> hashes) throws IOException {
        long t = 0;
        for (String h : hashes) {
            Path p = objectPath(accountId, profileId, h);
            if (Files.exists(p)) t += Files.size(p);
        }
        return t;
    }

    public static Set<String> uniqueHashes(Manifest m) {
        Set<String> s = new HashSet<>();
        for (ManifestEntry e : m.entries()) s.add(e.sha256());
        return s;
    }

    public void deleteAccountData(String accountId) throws IOException {
        deleteTree(layout.accountDir(accountId));
        deleteTree(layout.partial().resolve(accountId));
    }

    public static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(p);
        }
    }

    static boolean sameHash(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }
}
