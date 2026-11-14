package io.github.azotorp.wowuisync.client.sync;

import io.github.azotorp.wowuisync.client.net.ServerConnection;
import io.github.azotorp.wowuisync.client.util.CancelledException;
import io.github.azotorp.wowuisync.client.util.ClientLog;
import io.github.azotorp.wowuisync.client.util.UserException;
import io.github.azotorp.wowuisync.common.Defaults;
import io.github.azotorp.wowuisync.common.codec.Codec;
import io.github.azotorp.wowuisync.common.codec.CompressionPolicy;
import io.github.azotorp.wowuisync.common.hash.Hashing;
import io.github.azotorp.wowuisync.common.pack.PackEntry;
import io.github.azotorp.wowuisync.common.pack.PackKind;
import io.github.azotorp.wowuisync.common.pack.PackReader;
import io.github.azotorp.wowuisync.common.pack.PackWriter;
import io.github.azotorp.wowuisync.common.path.SyncPaths;
import io.github.azotorp.wowuisync.common.protocol.ErrorCodes;
import io.github.azotorp.wowuisync.common.protocol.Frame;
import io.github.azotorp.wowuisync.common.protocol.Msg;
import io.github.azotorp.wowuisync.common.protocol.ProtocolException;
import io.github.azotorp.wowuisync.common.protocol.Wire;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client side of transfers (DESIGN.md §11.5): N data connections pull jobs from a shared queue.
 * Large files go first, then raw and compressed packs interleaved. Completed objects are never re-sent;
 * large files resume from an offset. Connections reconnect with backoff 1, 2, 5, 10, 30 s; after 5 failures the
 * operation pauses until the user presses Resume.
 */
public final class TransferEngine {
    private static final int[] BACKOFF = {1, 2, 5, 10, 30};

    /** UI hooks. */
    public interface Sink {
        void stats(TransferStats s);

        void connectionLost(String message);
    }

    /** A file to upload: content hash, size, local path (relative path for sorting and categories). */
    public record UploadItem(String sha256, long size, String relPath, Path file) {}

    /** A file to download. */
    public record DownloadItem(String sha256, long size) {}

    private interface Job {
        void run(Wire w) throws IOException, UserException, CancelledException;

        String describe();
    }

    private final ServerConnection conn;
    private final int connections;
    private final TransferControl control;
    private final Sink sink;
    private final TransferStats stats;

    public TransferEngine(ServerConnection conn, int connections, TransferControl control, Sink sink, TransferStats stats) {
        this.conn = conn;
        int max = conn.login() != null ? conn.login().maxDataConnections() : Defaults.MAX_DATA_CONNECTIONS;
        this.connections = Math.max(1, Math.min(connections, max));
        this.control = control;
        this.sink = sink;
        this.stats = stats;
    }

    // ------------------------------------------------------------------ upload

    public void upload(String pushId, List<UploadItem> items, Map<String, Long> partialOffsets, CompressionPolicy policy)
            throws IOException, UserException, CancelledException {
        List<Job> large = new ArrayList<>();
        List<UploadItem> raw = new ArrayList<>();
        List<UploadItem> comp = new ArrayList<>();
        for (UploadItem it : items) {
            if (it.size() >= Defaults.LARGE_FILE_THRESHOLD) {
                boolean c = policy.shouldCompress(it.relPath(), it.sha256(), max -> readHead(it.file(), max));
                large.add(uploadLarge(pushId, it, c));
            } else {
                boolean c = policy.shouldCompress(it.relPath(), it.sha256(), max -> readHead(it.file(), max));
                (c ? comp : raw).add(it);
            }
        }
        Comparator<UploadItem> order = Comparator.comparing((UploadItem i) -> SyncPaths.extension(i.relPath()))
                .thenComparing(i -> SyncPaths.key(i.relPath()));
        raw.sort(order);
        comp.sort(order);
        List<Job> rawPacks = packs(pushId, raw, PackKind.RAW, Defaults.RAW_PACK_BYTES);
        List<Job> compPacks = packs(pushId, comp, PackKind.COMPRESSED, Defaults.COMPRESSED_PACK_BYTES);
        large.sort(Comparator.comparing(Job::describe));
        List<Job> jobs = new ArrayList<>(large);
        int i = 0, j = 0;
        while (i < rawPacks.size() || j < compPacks.size()) {
            if (i < rawPacks.size()) jobs.add(rawPacks.get(i++));
            if (j < compPacks.size()) jobs.add(compPacks.get(j++));
        }
        run(jobs);
    }

    private static byte[] readHead(Path f, int max) throws IOException {
        try (InputStream in = Files.newInputStream(f)) {
            return in.readNBytes(max);
        }
    }

    private List<Job> packs(String pushId, List<UploadItem> items, PackKind kind, long limit) {
        List<Job> out = new ArrayList<>();
        List<UploadItem> cur = new ArrayList<>();
        long size = 0;
        for (UploadItem it : items) {
            if (!cur.isEmpty() && size + it.size() > limit) {
                out.add(uploadPack(pushId, cur, kind));
                cur = new ArrayList<>();
                size = 0;
            }
            cur.add(it);
            size += it.size();
        }
        if (!cur.isEmpty()) out.add(uploadPack(pushId, cur, kind));
        return out;
    }

    private Job uploadPack(String pushId, List<UploadItem> items, PackKind kind) {
        return new Job() {
            @Override
            public void run(Wire w) throws IOException, UserException {
                Codec codec = conn.codec() == Codec.RAW ? Codec.preferred() : conn.codec();
                PackWriter pw = new PackWriter(kind, codec, Defaults.ZSTD_LEVEL);
                long bytes = 0;
                for (UploadItem it : items) {
                    byte[] data = Files.readAllBytes(it.file());
                    if (data.length != it.size() || !Hashing.sha256Hex(data).equals(it.sha256())) {
                        throw new UserException("A file changed while pushing: " + it.relPath() + ". Push again.");
                    }
                    pw.add(it.relPath(), it.sha256(), data);
                    bytes += data.length;
                }
                byte[] pack = pw.build();
                int id = w.newRequestId();
                w.sendBinary(id, new Msg.PackUpload(pushId), pack, 0, pack.length);
                w.read().message(Msg.Ok.class);
                stats.wireBytes.addAndGet(pack.length);
                stats.bytesDone.addAndGet(bytes);
                stats.filesDone.addAndGet(items.size());
            }

            @Override
            public String describe() {
                return kind + " pack of " + items.size();
            }
        };
    }

    private Job uploadLarge(String pushId, UploadItem it, boolean compress) {
        return new Job() {
            long counted;

            @Override
            public void run(Wire w) throws IOException, UserException, CancelledException {
                stats.bytesDone.addAndGet(-counted);
                counted = 0;
                Msg.ResumeOffset ro = w.call(new Msg.ResumeQuery(pushId, it.sha256()), Msg.ResumeOffset.class);
                if (ro.offset() < 0) {
                    stats.bytesDone.addAndGet(it.size());
                    stats.filesDone.incrementAndGet();
                    return;
                }
                long offset = ro.offset() > it.size() ? 0 : ro.offset();
                stats.bytesDone.addAndGet(offset);
                counted = offset;
                byte[] buf = new byte[Defaults.CHUNK_BYTES];
                try (RandomAccessFile raf = new RandomAccessFile(it.file().toFile(), "r")) {
                    if (raf.length() != it.size()) throw new UserException("A file changed while pushing: " + it.relPath() + ". Push again.");
                    raf.seek(offset);
                    if (offset == it.size()) {
                        send(w, buf, 0, offset, true);
                    }
                    while (offset < it.size()) {
                        control.checkpoint();
                        int n = raf.read(buf, 0, (int) Math.min(buf.length, it.size() - offset));
                        if (n <= 0) throw new UserException("A file changed while pushing: " + it.relPath() + ". Push again.");
                        boolean last = offset + n >= it.size();
                        send(w, buf, n, offset, last);
                        offset += n;
                        stats.bytesDone.addAndGet(n);
                        counted += n;
                    }
                }
                stats.filesDone.incrementAndGet();
            }

            private void send(Wire w, byte[] buf, int n, long offset, boolean last) throws IOException {
                int id = w.newRequestId();
                w.sendBinary(id, new Msg.FileUploadChunk(pushId, it.sha256(), offset, it.size(), last, compress), buf, 0, n);
                Frame f = w.read();
                Msg.Ok ok = f.message(Msg.Ok.class);
                stats.wireBytes.addAndGet(n);
                if ("have".equals(ok.message()) && !last) throw new AlreadyThere();
            }

            @Override
            public String describe() {
                return it.relPath();
            }
        };
    }

    /** The server already has the object (another device or connection stored it). */
    private static final class AlreadyThere extends IOException {
        AlreadyThere() {
            super("already stored");
        }
    }

    // ------------------------------------------------------------------ download

    /** Downloads objects into {@code staged} as {@code <staged>/<sha256>}; skips ones already staged. */
    public void download(String accountId, String profileId, List<DownloadItem> items, Path staged)
            throws IOException, UserException, CancelledException {
        Files.createDirectories(staged);
        List<Job> large = new ArrayList<>();
        List<Job> small = new ArrayList<>();
        List<DownloadItem> batch = new ArrayList<>();
        long batchBytes = 0;
        for (DownloadItem it : items) {
            Path done = staged.resolve(it.sha256());
            if (Files.isRegularFile(done) && Files.size(done) == it.size() && verifyStaged(done,it.sha256())) {
                stats.bytesDone.addAndGet(it.size());
                stats.filesDone.incrementAndGet();
                continue;
            }
            Files.deleteIfExists(done);
            if (it.size() >= Defaults.LARGE_FILE_THRESHOLD) {
                large.add(downloadLarge(accountId, profileId, it, staged));
                continue;
            }
            if (!batch.isEmpty() && (batchBytes + it.size() > Defaults.COMPRESSED_PACK_BYTES || batch.size() >= 5000)) {
                small.add(fetch(accountId, profileId, batch, staged));
                batch = new ArrayList<>();
                batchBytes = 0;
            }
            batch.add(it);
            batchBytes += it.size();
        }
        if (!batch.isEmpty()) small.add(fetch(accountId, profileId, batch, staged));
        List<Job> jobs = new ArrayList<>(large);
        jobs.addAll(small);
        run(jobs);
    }

    /** Fetches small objects into memory (for .toc files before the selection window). */
    public static Map<String, byte[]> fetchToMemory(ServerConnection conn, String accountId, String profileId, List<String> hashes)
            throws IOException {
        Map<String, byte[]> out = new java.util.HashMap<>();
        if (hashes.isEmpty()) return out;
        try (Wire w = conn.openData()) {
            for (int i = 0; i < hashes.size(); i += 2000) {
                List<String> part = hashes.subList(i, Math.min(hashes.size(), i + 2000));
                int id = w.newRequestId();
                w.send(id, new Msg.Fetch(accountId, profileId, part));
                while (true) {
                    Frame f = w.read();
                    Msg.PackDownload pd = f.message(Msg.PackDownload.class);
                    for (PackEntry e : PackReader.read(f.payload(), f.bodyOffset(), f.bodyLength(), true).entries()) out.put(e.sha256(), e.data());
                    if (pd.last()) break;
                }
            }
        }
        return out;
    }

    private Job fetch(String accountId, String profileId, List<DownloadItem> items, Path staged) {
        return new Job() {
            @Override
            public void run(Wire w) throws IOException {
                List<String> hashes = new ArrayList<>(items.size());
                for (DownloadItem it : items) {
                    Path f=staged.resolve(it.sha256());
                    if(!Files.isRegularFile(f) || Files.size(f)!=it.size() || !verifyStaged(f,it.sha256())) { Files.deleteIfExists(f);hashes.add(it.sha256()); }
                }
                if (hashes.isEmpty()) return;
                int id = w.newRequestId();
                w.send(id, new Msg.Fetch(accountId, profileId, hashes));
                while (true) {
                    Frame f = w.read();
                    Msg.PackDownload pd = f.message(Msg.PackDownload.class);
                    stats.wireBytes.addAndGet(f.bodyLength());
                    PackReader.Pack pack;
                    try {
                        pack = PackReader.read(f.payload(), f.bodyOffset(), f.bodyLength(), true);
                    } catch (IOException e) {
                        throw new ProtocolException(ErrorCodes.INTEGRITY, "Downloaded data failed verification: " + e.getMessage());
                    }
                    for (PackEntry e : pack.entries()) {
                        DownloadItem wanted=items.stream().filter(x->x.sha256().equals(e.sha256())).findFirst().orElseThrow(()->new IOException("Unexpected object"));
                        if(wanted.size()!=e.data().length) throw new IOException("Downloaded object size mismatch");
                        writeStaged(staged, e.sha256(), e.data());
                        stats.bytesDone.addAndGet(e.data().length);
                        stats.filesDone.incrementAndGet();
                    }
                    if (pd.last()) break;
                }
            }

            @Override
            public String describe() {
                return "fetch " + items.size();
            }
        };
    }

    private Job downloadLarge(String accountId, String profileId, DownloadItem it, Path staged) {
        return new Job() {
            long counted;

            @Override
            public void run(Wire w) throws IOException, CancelledException {
                stats.bytesDone.addAndGet(-counted);
                counted = 0;
                Path part = staged.getParent().resolve(it.sha256() + ".part");
                long offset = Files.exists(part) ? Files.size(part) : 0;
                if (offset > it.size()) {
                    Files.delete(part);
                    offset = 0;
                }
                stats.bytesDone.addAndGet(offset);
                counted = offset;
                int id = w.newRequestId();
                w.send(id, new Msg.FileDownloadChunk(accountId, profileId, it.sha256(), offset, 0, false));
                try (OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    while (true) {
                        Frame f = w.read();
                        Msg.FileDownloadChunk ch = f.message(Msg.FileDownloadChunk.class);
                        if (ch.offset()!=offset || ch.totalSize()!=it.size() || !it.sha256().equals(ch.hash()) || f.bodyLength()>it.size()-offset ||
                            ch.last()!=(offset+f.bodyLength()==it.size())) throw new IOException("Unexpected download chunk");
                        out.write(f.payload(), f.bodyOffset(), f.bodyLength());
                        offset += f.bodyLength();
                        stats.bytesDone.addAndGet(f.bodyLength());
                        stats.wireBytes.addAndGet(f.bodyLength());
                        counted += f.bodyLength();
                        if (ch.last()) break;
                        if (control.cancelled()) throw new CancelledException();
                    }
                }
                if (offset != it.size() || !Hashing.sha256Hex(part).equals(it.sha256())) {
                    Files.deleteIfExists(part);
                    stats.bytesDone.addAndGet(-counted);
                    counted = 0;
                    throw new ProtocolException(ErrorCodes.INTEGRITY, "A downloaded file failed verification.");
                }
                moveInto(part, staged.resolve(it.sha256()));
                stats.filesDone.incrementAndGet();
            }

            @Override
            public String describe() {
                return it.sha256();
            }
        };
    }

    static void writeStaged(Path staged, String sha, byte[] data) throws IOException {
        if(!Hashing.sha256Hex(data).equals(sha)) throw new IOException("Staged hash mismatch");
        Path tmp = staged.resolve(sha + "." + UUID.randomUUID() + ".tmp");
        Files.write(tmp, data);
        moveInto(tmp, staged.resolve(sha));
    }

    static void moveInto(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Verifies a staged object's hash. */
    public static boolean verifyStaged(Path file, String sha) {
        try {
            MessageDigest md = Hashing.newSha256();
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return Hashing.hex(md.digest()).equals(sha);
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ worker pool

    private void run(List<Job> jobs) throws IOException, UserException, CancelledException {
        if (jobs.isEmpty()) return;
        ConcurrentLinkedQueue<Job> queue = new ConcurrentLinkedQueue<>(jobs);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        int n = Math.min(connections, jobs.size());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            threads.add(Thread.ofPlatform().daemon().name("transfer-" + i).start(() -> worker(queue, failure)));
        }
        Thread reporter = Thread.ofPlatform().daemon().name("transfer-stats").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                sink.stats(stats);
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        try {
            for (Thread t : threads) t.join();
        } catch (InterruptedException e) {
            control.cancel();
            throw new CancelledException();
        } finally {
            reporter.interrupt();
            sink.stats(stats);
        }
        Throwable f = failure.get();
        if (f instanceof CancelledException c) throw c;
        if (f instanceof UserException u) throw u;
        if (f instanceof IOException io) throw io;
        if (f != null) throw new IOException(f.getMessage(), f);
        if (control.cancelled()) throw new CancelledException();
    }

    private void worker(ConcurrentLinkedQueue<Job> queue, AtomicReference<Throwable> failure) {
        Wire w = null;
        int failures = 0;
        try {
            Job job;
            while (failure.get() == null && (job = queue.poll()) != null) {
                int integrityRetries = 0;
                while (true) {
                    control.checkpoint();
                    if (failure.get() != null) return;
                    try {
                        if (w == null) w = conn.openData();
                        job.run(w);
                        failures = 0;
                        break;
                    } catch (AlreadyThere e) {
                        break;
                    } catch (ProtocolException e) {
                        boolean retryable = ErrorCodes.INTEGRITY.equals(e.code())
                                || (ErrorCodes.CONFLICT.equals(e.code()) && String.valueOf(e.getMessage()).startsWith("offset:"));
                        if (retryable && integrityRetries++ == 0) {
                            ClientLog.LOG.info("Integrity retry for " + job.describe());
                            continue;
                        }
                        throw e;
                    } catch (IOException e) {
                        if (w != null) w.close();
                        w = null;
                        if (conn.isClosed()) throw new UserException("The connection to the server was lost. Connect again; "
                                + "files already transferred will not be sent again.");
                        failures++;
                        ClientLog.LOG.info("Data connection failed (" + failures + "): " + e.getMessage());
                        if (failures > BACKOFF.length) {
                            sink.connectionLost("Connection problems: " + e.getMessage() + ". Press Resume to try again.");
                            failures = 0;
                            continue;
                        }
                        sleep(BACKOFF[failures - 1]);
                    }
                }
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        } finally {
            if (w != null) w.close();
        }
    }

    private void sleep(int seconds) throws CancelledException {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            if (control.cancelled()) throw new CancelledException();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new CancelledException();
            }
        }
    }
}
