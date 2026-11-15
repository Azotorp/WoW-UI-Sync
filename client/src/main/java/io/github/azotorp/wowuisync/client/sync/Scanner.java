package io.github.azotorp.wowuisync.client.sync;
import io.github.azotorp.wowuisync.client.data.ClientDb;
import io.github.azotorp.wowuisync.client.data.ClientDb.ScanCache;
import io.github.azotorp.wowuisync.client.util.*;
import io.github.azotorp.wowuisync.common.hash.Hashing;
import io.github.azotorp.wowuisync.common.manifest.*;
import io.github.azotorp.wowuisync.common.path.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Bounded hash I/O, precise identity-aware cache and durable checkpoints throughout a scan. */
public final class Scanner {
    public interface Progress {
        void update(int done, int total);
        default void details(String text) {}
        default void metrics(int cacheHits, int hashed, long bytesRead, double elapsedSeconds, double etaSeconds, int workers) {}
        default void currentFile(String path) {}
    }
    private record Found(String rel, Path abs, long size, String modified, String identity) {}
    private record Result(Found file, String sha) {}
    private Scanner() {}
    private static final ThreadLocal<byte[]> HASH_BUFFER = ThreadLocal.withInitial(() -> new byte[256*1024]);
    private static Found found(String rel, Path path, BasicFileAttributes a, boolean unix) throws IOException {
        String identity = String.valueOf(a.fileKey()) + ":" + a.creationTime();
        if (unix) identity += ":" + Files.getAttribute(path,"unix:ctime",LinkOption.NOFOLLOW_LINKS);
        return new Found(rel,path,a.size(),a.lastModifiedTime().toString(),identity);
    }
    private static Found stat(Found f, boolean unix) throws IOException {
        return found(f.rel(),f.abs(),Files.readAttributes(f.abs(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS),unix);
    }
    private static Result hash(Found f, BooleanSupplier cancelled, boolean unix) throws IOException, CancelledException {
        check(cancelled);
        if (!f.equals(stat(f,unix))) throw new IOException("File changed before hashing; retry scan");
        var md = Hashing.newSha256();
        byte[] buffer = HASH_BUFFER.get();
        try (InputStream in = Files.newInputStream(f.abs())) {
            int n; while ((n=in.read(buffer))!=-1) { check(cancelled);md.update(buffer,0,n); }
        }
        if (!f.equals(stat(f,unix))) throw new IOException("File changed during hashing; retry scan");
        return new Result(f,Hashing.hex(md.digest()));
    }
    private static void check(BooleanSupplier cancelled) throws CancelledException {
        if (Thread.currentThread().isInterrupted() || cancelled != null && cancelled.getAsBoolean()) throw new CancelledException();
    }
    public static LocalScan scan(Path flavor, Exclusions exclusions, ClientDb db, Progress progress, BooleanSupplier cancelled)
            throws IOException, SQLException, CancelledException {
        long start=System.nanoTime();
        flavor=flavor.toRealPath();
        boolean unix=Files.getFileStore(flavor).supportsFileAttributeView("unix");
        List<Found> found=new ArrayList<>(); List<String> invalid=new ArrayList<>();
        try {
            walk(flavor,flavor.resolve("WTF"),exclusions,found,invalid,cancelled,unix);
            Path addons=flavor.resolve("Interface/AddOns");
            if (Files.isDirectory(addons,LinkOption.NOFOLLOW_LINKS)) try (var stream=Files.list(addons)) {
                for (Path folder : (Iterable<Path>)stream::iterator) {
                    check(cancelled);
                    if (Files.isSymbolicLink(folder)) throw new IOException("Symlink add-on folder must be excluded or replaced before syncing");
                    if (Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS)) walk(flavor,folder,exclusions,found,invalid,cancelled,unix);
                }
            }
        } catch (java.io.InterruptedIOException e) { throw new CancelledException(); }
        long traversal=System.nanoTime()-start;
        String root=flavor.toString();
        Map<String,ScanCache> cache=db.scanCache(root);
        List<Found> jobs=new ArrayList<>(); Map<String,String> hashes=new HashMap<>();
        long now=System.currentTimeMillis(); int hits=0,hashed=0; long read=0;
        for (Found f : found) {
            ScanCache c=cache.get(f.rel());
            // Periodic validation also bounds trust on systems without Unix change timestamps.
            if (c!=null && c.size()==f.size() && c.modified().equals(f.modified()) && c.identity().equals(f.identity())
                    && java.time.Instant.parse(f.modified()).toEpochMilli()<=now+2000
                    && now>=c.verifiedAt() && now-c.verifiedAt()<24*60*60*1000L) { hashes.put(f.rel(),c.sha256());hits++; }
            else jobs.add(f);
        }
        int total=found.size(),done=hits;
        if (progress!=null) progress.update(done,total);
        int threads=Math.max(1,Math.min(8,Integer.getInteger("wowuisync.hashThreads",io.github.azotorp.wowuisync.common.hash.ParallelHasher.defaultThreads())));
        if(progress!=null)progress.metrics(hits,0,0,(System.nanoTime()-start)/1e9,-1,threads);
        ExecutorService pool=Executors.newFixedThreadPool(threads);
        CompletionService<Result> results=new ExecutorCompletionService<>(pool);
        List<ScanCache> pending=new ArrayList<>();
        long checkpoint=System.nanoTime(),persistNanos=0,lastUi=0;
        int submitted=0,completed=0;
        try {
            while(completed<jobs.size()) {
                check(cancelled);
                while(submitted<jobs.size() && submitted-completed<threads*2) {
                    Found f=jobs.get(submitted++);results.submit(()->hash(f,cancelled,unix));
                }
                Future<Result> future=results.poll(100,TimeUnit.MILLISECONDS);
                if(future==null) continue;
                Result result=future.get();completed++;hashed++;done++;read+=result.file().size();
                Found f=result.file();hashes.put(f.rel(),result.sha());
                pending.add(new ScanCache(f.rel(),f.size(),f.modified(),f.identity(),result.sha(),now));
                long tick=System.nanoTime();
                if(pending.size()>=64 || tick-checkpoint>=1_000_000_000L) {
                    long before=System.nanoTime();db.checkpointScanCache(root,pending);pending.clear();
                    persistNanos+=System.nanoTime()-before;checkpoint=tick;
                }
                if(progress!=null && (tick-lastUi>=100_000_000L || done==total)) {
                    progress.update(done,total);
                    double seconds=(tick-start)/1e9;
                    progress.metrics(hits,hashed,read,seconds,hashed>0?(jobs.size()-hashed)*seconds/hashed:-1,threads);
                    progress.currentFile(f.rel());
                    lastUi=tick;
                }
            }
        } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new CancelledException(); }
        catch(ExecutionException e) {
            if(e.getCause() instanceof CancelledException) throw new CancelledException();
            throw new IOException("Scan could not safely read every file; no sync plan was created",e.getCause());
        } finally {
            pool.shutdownNow();
            db.checkpointScanCache(root,pending);
        }
        check(cancelled);
        List<ManifestEntry> entries=new ArrayList<>();Map<String,Path> files=new HashMap<>();
        for(Found f:found) {
            if(!f.equals(stat(f,unix))) throw new IOException("Installation changed during scan; retry after closing external add-on tools");
            entries.add(new ManifestEntry(f.rel(),f.size(),hashes.get(f.rel())));files.put(SyncPaths.key(f.rel()),f.abs());
        }
        double elapsed=(System.nanoTime()-start)/1e9;
        String summary=String.format(Locale.ROOT,"Scan complete: %d files, %d cache hits, %d hashed, %d bytes read; traversal %.3fs, cache writes %.3fs, elapsed %.3fs",total,hits,hashed,read,traversal/1e9,persistNanos/1e9,elapsed);
        ClientLog.LOG.info(summary);if(progress!=null) { progress.update(total,total);progress.metrics(hits,hashed,read,elapsed,-1,threads);progress.currentFile("");progress.details(summary); }
        List<String> paths=entries.stream().map(ManifestEntry::path).toList();
        return new LocalScan(flavor,new Manifest(entries),files,invalid,SyncPaths.caseCollisions(paths));
    }
    private static void walk(Path flavor, Path dir, Exclusions ex, List<Found> out, List<String> invalid, BooleanSupplier cancelled, boolean unix) throws IOException {
        if (Files.isSymbolicLink(dir)) throw new IOException("Symlink sync root is unsupported");
        if (!Files.isDirectory(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                if (cancelled != null && cancelled.getAsBoolean()) throw new java.io.InterruptedIOException("Scan cancelled");
                // Skip folders whose whole content is excluded (e.g. .git): probe with an unlikely nested name.
                String rel = rel(flavor, d);
                if (ex.isExcluded(rel + "/wus-probe-7f3a/x.wusprobe") && ex.isExcluded(rel + "/x.wusprobe")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                if (cancelled != null && cancelled.getAsBoolean()) throw new java.io.InterruptedIOException("Scan cancelled");
                if (attrs.isSymbolicLink()) throw new IOException("Symlink sync content is unsupported");
                if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                String rel = rel(flavor, f);
                if (!SyncPaths.isSyncedPath(rel) || ex.isExcluded(rel)) return FileVisitResult.CONTINUE;
                String bad = SyncPaths.invalidWindowsNameReason(rel);
                if (bad != null) {
                    invalid.add(rel + " (" + bad + ")");
                    return FileVisitResult.CONTINUE;
                }
                out.add(found(rel, f, attrs, unix));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path f, IOException e) throws IOException {
                throw new IOException("Cannot read a selected sync path. Fix access or exclude it before syncing.", e);
            }
        });
    }

    static String rel(Path flavor, Path p) {
        return flavor.relativize(p).toString().replace('\\', '/');
    }
}
