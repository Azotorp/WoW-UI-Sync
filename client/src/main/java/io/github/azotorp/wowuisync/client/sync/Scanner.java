package io.github.azotorp.wowuisync.client.sync;
import io.github.azotorp.wowuisync.client.data.ClientDb;
import io.github.azotorp.wowuisync.client.util.CancelledException;
import io.github.azotorp.wowuisync.common.chunk.*;
import io.github.azotorp.wowuisync.common.hash.Hashing;
import io.github.azotorp.wowuisync.common.manifest.*;
import io.github.azotorp.wowuisync.common.path.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.concurrent.*;

/** Fail-closed metadata scan with persistent chunk manifests; strict scans verify content. */
public final class Scanner {
    /** During discovery, total is zero and done is the negative discovered count. */
    public interface Progress {
        void update(int done,int total);
        default void phase(String phase) {}
        default void statistics(Statistics statistics) {}
    }
    public record Statistics(int discovered,int cacheHits,int hashedFiles,long bytesHashed,long discoveryNanos,
            long cacheNanos,long metadataNanos,long hashingNanos,long manifestNanos,long persistenceNanos,long elapsedNanos,
            String mode, Map<String,Integer> misses, int workers) {}
    private record Found(Path path, BasicFileAttributes attrs) {}
    private static void phase(Progress p,String text) { if(p!=null)p.phase(text); }
    private static final class ScanCancelled extends IOException {}
    private static void checkCancelled(BooleanSupplier cancelled) throws ScanCancelled {
        if(Thread.currentThread().isInterrupted() || (cancelled!=null && cancelled.getAsBoolean())) throw new ScanCancelled();
    }
    private static void validateName(String path) throws IOException {
        if(!SyncPaths.isSafeRelative(path) || !SyncPaths.isSyncedPath(path) ||
                SyncPaths.invalidWindowsNameReason(path)!=null || path.length()>1024)
            throw new IOException("Unsafe sync path: "+path);
    }
    public static LocalScan scan(Path flavor,Exclusions ex,ClientDb db,Progress progress,BooleanSupplier cancelled)
            throws IOException,SQLException,CancelledException {
        return scan(flavor,ex,db,progress,cancelled,false);
    }
    public static LocalScan verified(Path flavor,Exclusions ex,ClientDb db,Progress progress,BooleanSupplier cancelled)
            throws IOException,SQLException,CancelledException {
        return scan(flavor,ex,db,progress,cancelled,true);
    }
    private static final Map<Object,java.util.concurrent.atomic.AtomicBoolean> ACTIVE=new HashMap<>();
    private static boolean closing;
    /** Called off the EDT on window-close; preserve the last verified batch before closing SQLite. */
    public static void cancelAndAwait() {
        boolean interrupted=false;
        synchronized(ACTIVE) {
            closing=true;
            ACTIVE.values().forEach(flag->flag.set(true));
            while(!ACTIVE.isEmpty())try {ACTIVE.wait(100);}catch(InterruptedException e){interrupted=true;}
        }
        if(interrupted)Thread.currentThread().interrupt();
    }
    private static LocalScan scan(Path flavor,Exclusions ex,ClientDb db,Progress progress,BooleanSupplier cancelled,boolean verify)
            throws IOException,SQLException,CancelledException {
        Object token=new Object();var stop=new java.util.concurrent.atomic.AtomicBoolean();
        synchronized(ACTIVE){if(closing)throw new CancelledException();ACTIVE.put(token,stop);}
        try {return execute(flavor,ex,db,progress,()->stop.get()||(cancelled!=null&&cancelled.getAsBoolean()),verify);}
        finally {synchronized(ACTIVE){ACTIVE.remove(token);ACTIVE.notifyAll();}}
    }
    private static LocalScan execute(Path flavor,Exclusions ex,ClientDb db,Progress progress,BooleanSupplier cancelled,boolean verify)
            throws IOException,SQLException,CancelledException {
        long start=System.nanoTime();
        try {
            phase(progress,"Discovering local files");
            if(progress!=null) progress.update(0,0);
            checkCancelled(cancelled);
            Path base=flavor.toAbsolutePath().normalize();
            if(!Files.isDirectory(base)) throw new IOException("Installation is missing or unreadable");
            List<Found> files=new ArrayList<>();long[] discoveryReport={0};
            Path wtf=SyncPaths.resolve(base,"WTF/.wusprobe"), addons=SyncPaths.resolve(base,"Interface/AddOns/.probe/.probe");
            walk(base,wtf.getParent(),ex,files,progress,cancelled,discoveryReport);
            Path ad=addons.getParent().getParent();
            BasicFileAttributes addonAttributes=null;
            try {addonAttributes=Files.readAttributes(ad,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
            catch(NoSuchFileException absent) { /* no addon root yet */ }
            if(addonAttributes!=null) {
                if(!addonAttributes.isDirectory() || addonAttributes.isSymbolicLink() || addonAttributes.isOther())throw new IOException("Unsafe addon root");
                try(var s=Files.list(ad)) { for(Path p:(Iterable<Path>)s::iterator) {
                    checkCancelled(cancelled);
                    if(ex.isExcluded(rel(base,p)+"/x")) continue;
                    var attributes=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    if(attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Linked/special addon directory: "+p);
                    if(attributes.isDirectory()) walk(base,p,ex,files,progress,cancelled,discoveryReport);
                }}
            }
            long discoveredAt=System.nanoTime();
            phase(progress,"Loading cached file metadata");
            Map<String,ScanCache.Entry> cache=db.scanCache(base.toString());
            Map<String,ScanCache.Entry> rows=new HashMap<>(), batch=new HashMap<>();
            Set<String> corrupt=db.corruptScanCachePaths(base.toString());
            Set<String> known=new HashSet<>(cache.keySet());known.addAll(corrupt);
            long loadedAt=System.nanoTime();
            if(progress!=null) progress.update(0,files.size());
            List<ManifestEntry> entries=new ArrayList<>();Map<String,Path> byKey=new HashMap<>();
            int done=0,hits=0,hashed=0;long bytes=0,metadataNanos=0,hashingNanos=0,persistenceNanos=0;
            long now=System.currentTimeMillis();
            String mode=verify?"Strict verification":cache.isEmpty()?"First cache fill":"Cached check";
            int workers=workers();
            Map<String,Integer> misses=new TreeMap<>();
            // At most workers outstanding tasks, never one queued task per discovered file.
            // A large CDC task holds <17 MiB of content buffers, regardless of file length.
            // 64 MiB permits cap aggregate in-flight buffers independently of worker count.
            Semaphore memory=new Semaphore(64);
            var bytesRead=new java.util.concurrent.atomic.AtomicLong();
            var completed=new ConcurrentHashMap<String,ScanCache.Entry>();
            ThreadPoolExecutor pool=new ThreadPoolExecutor(workers,workers,0,TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(workers), runnable -> new Thread(runnable,"wus-scan"));
            List<Future<Hashed>> pending=new ArrayList<>();
            phase(progress,mode+" · metadata comparison and content hashing/chunking ("+workers+" workers)");
            long report=0;
            try {
                for(int offset=0;offset<files.size();offset+=workers) {
                    checkCancelled(cancelled);
                    pending.clear();

                    for(int i=offset;i<Math.min(files.size(),offset+workers);i++) {
                        Found found=files.get(i);String relative=rel(base,found.path());
                        long metaStart=System.nanoTime();
                        ScanCache.Stamp stamp=ScanCache.stamp(found.path(),found.attrs());
                        metadataNanos+=System.nanoTime()-metaStart;
                        ScanCache.Entry cached=cache.get(relative);
                        String reason=verify?"strict verification":corrupt.contains(relative)?"corrupt entry":cached==null?"absent entry":
                            stamp.identity()==null?"unavailable identity":!stamp.equals(cached.stamp())?"changed metadata":
                            !stamp.reusable(now)?"recent timestamp":null;
                        if(reason==null) pending.add(CompletableFuture.completedFuture(new Hashed(cached,0,0,true)));
                        else {
                            misses.merge(reason,1,Integer::sum);
                            Callable<Hashed> task=()-> {
                                {
                                    checkCancelled(cancelled);
                                    long hashStart=System.nanoTime();
                                    // Re-read metadata immediately before content. Discovery metadata can be
                                    // stale when an external updater (e.g. TSM) writes earlier in the scan.
                                    for(int attempt=0;;attempt++) {
                                        try {
                                            HashedFile actual=hashContent(base,found.path(),null,cancelled,bytesRead::addAndGet,memory,null);
                                            var hint=new ScanCache.Entry(actual.stamp(),actual.manifest());completed.put(relative,hint);
                                            return new Hashed(hint,actual.manifest().size(),System.nanoTime()-hashStart,false);
                                        } catch(SourceChanged changed) { if(attempt>=1)throw changed; }
                                    }
                                }
                            };
                            if(workers==1)try {pending.add(CompletableFuture.completedFuture(task.call()));}
                            catch(IOException e){throw e;}catch(Exception e){throw new IOException("Hashing failed",e);}
                            else pending.add(pool.submit(task));
                        }
                    }
                    // Only the coordinator reports progress and writes SQLite.

                    for(int j=0;j<pending.size();j++) {
                        Hashed result;
                        try {
                            while(true) {
                                checkCancelled(cancelled);
                                try {result=pending.get(j).get(100,TimeUnit.MILLISECONDS);break;}
                                catch(TimeoutException waiting) {
                                    long tick=System.nanoTime();
                                    if(progress!=null && tick-report>100_000_000L) {
                                        progress.update(done,files.size());
                                        progress.statistics(new Statistics(files.size(),hits,hashed,bytesRead.get(),discoveredAt-start,loadedAt-discoveredAt,metadataNanos,hashingNanos,0,persistenceNanos,tick-start,mode,Map.copyOf(misses),workers));report=tick;
                                    }
                                }
                            }
                        } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new ScanCancelled();}
                        catch(ExecutionException e) {
                            if(e.getCause() instanceof IOException io)throw io;
                            throw new IOException("Hash worker failed",e.getCause());
                        }
                        var entry=result.entry().manifest();
                        completed.remove(entry.path());
                        entries.add(entry);byKey.put(SyncPaths.key(entry.path()),files.get(offset+j).path());
                        rows.put(entry.path(),result.entry());
                        if(!result.entry().equals(cache.get(entry.path())))batch.put(entry.path(),result.entry());
                        if(result.hit())hits++;else {hashed++;bytes+=result.bytes();hashingNanos+=result.nanos();}
                        done++;
                        if(batch.size()>=128) {

                            long at=System.nanoTime();db.mergeScanCache(base.toString(),batch);batch.clear();persistenceNanos+=System.nanoTime()-at;
                        }
                        long tick=System.nanoTime();
                        if(progress!=null && tick-report>100_000_000L) {
                            phase(progress,mode+" · "+(result.hit()?"Metadata comparison":"Content hashing/chunking"));
                            progress.update(done,files.size());
                            progress.statistics(new Statistics(files.size(),hits,hashed,bytesRead.get(),discoveredAt-start,loadedAt-discoveredAt,metadataNanos,hashingNanos,0,persistenceNanos,tick-start,mode,Map.copyOf(misses),workers));report=tick;
                        }
                    }
                }
            } finally {
                for(var task:pending)task.cancel(true);
                pool.shutdownNow();
                // Workers must exit before the scan can return cancellation/failure.
                boolean interrupted=false;
                while(!pool.isTerminated())try {pool.awaitTermination(100,TimeUnit.MILLISECONDS);}
                catch(InterruptedException e) {interrupted=true;}
                if(interrupted)Thread.currentThread().interrupt();
                // Preserve only fully read/stable per-file hints; no manifest or baseline.
                batch.putAll(completed);
                if(!batch.isEmpty()) {long at=System.nanoTime();db.mergeScanCache(base.toString(),batch);persistenceNanos+=System.nanoTime()-at;}
            }
            long manifestStart=System.nanoTime();
            phase(progress,"Building local manifest");
            var collisions=SyncPaths.caseCollisions(entries.stream().map(ManifestEntry::path).toList());
            if(!collisions.isEmpty()) throw new IOException("Paths collide on Windows: "+collisions.getFirst());
            checkCancelled(cancelled);
            Manifest manifest=new Manifest(entries);
            long hashedAt=System.nanoTime();
            phase(progress,"Cache persistence · completing local manifest");
            db.completeScanCache(base.toString(),rows,known);
            checkCancelled(cancelled);
            long end=System.nanoTime();
            var stats=new Statistics(files.size(),hits,hashed,bytesRead.get(),discoveredAt-start,loadedAt-discoveredAt,metadataNanos,hashingNanos,hashedAt-manifestStart,persistenceNanos+end-hashedAt,end-start,mode,Map.copyOf(misses),workers);
            if(progress!=null){progress.update(done,files.size());progress.statistics(stats);}
            io.github.azotorp.wowuisync.client.util.ClientLog.LOG.info("Scan "+stats+" verified="+verify);
            return new LocalScan(base,manifest,byKey,List.of(),collisions);
        } catch(ScanCancelled e) { throw new CancelledException(); }
    }
    private static void walk(Path base,Path dir,Exclusions ex,List<Found> out,Progress progress,BooleanSupplier cancelled,long[] report) throws IOException {
        checkCancelled(cancelled);
        try { Files.readAttributes(dir,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS); }
        catch(NoSuchFileException missing) { return; }
        Files.walkFileTree(dir,new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path p,BasicFileAttributes a) throws IOException {
                checkCancelled(cancelled);
                String r=rel(base,p);
                if(ex.isExcluded(r+"/x") && ex.isExcluded(r+"/x/y")) return FileVisitResult.SKIP_SUBTREE;
                if(a.isSymbolicLink() || a.isOther()) throw new IOException("Linked directory");
                // Validate each directory once, rather than every ancestor for every cache hit.
                if(!p.toRealPath().equals(p.getParent().toRealPath().resolve(p.getFileName()).toRealPath(LinkOption.NOFOLLOW_LINKS)))
                    throw new IOException("Linked directory");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path p,BasicFileAttributes a) throws IOException {
                checkCancelled(cancelled);
                String r=rel(base,p);if(ex.isExcluded(r)) return FileVisitResult.CONTINUE;
                if(a.isSymbolicLink() || !a.isRegularFile()) throw new IOException("Unsupported linked/special file: "+r);
                // Full link/ancestor validation remains immediately before reading each file.
                // Do not repeat those expensive Windows filesystem calls during discovery.
                validateName(r);out.add(new Found(p,a));
                if(progress!=null && out.size()%100==0 && System.nanoTime()-report[0]>100_000_000L) {progress.update(-out.size(),0);report[0]=System.nanoTime();}
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path p,IOException error) throws IOException { throw error; }
        });
    }
    private record Hashed(ScanCache.Entry entry,long bytes,long nanos,boolean hit) {}
    private static final class SourceChanged extends IOException {
        SourceChanged(String path) {super("File keeps changing while scanning: "+path+". Pause external addon updaters (including TSM) and retry.");}
    }
    /** 1 is the sequential HDD/network-drive fallback; max 4 plus a separate content-memory budget. */
    static int workers() {return Math.clamp(Integer.getInteger("wowuisync.scanWorkers",1),1,4);}
    /** Scan without staging; a non-null staging path is reserved for explicit upload preparation. */
    static ManifestEntry hashFile(Path base,Path p,Path staged,BooleanSupplier cancelled) throws IOException {
        return hashContent(base,p,staged,cancelled,ignored->{},null,null).manifest();
    }
    private record HashedFile(ManifestEntry manifest,ScanCache.Stamp stamp) {}
    private static HashedFile hashContent(Path base,Path p,Path staged,BooleanSupplier cancelled,
            java.util.function.LongConsumer bytesRead,Semaphore memory,Set<String> requested) throws IOException {
        if(staged!=null)Files.createDirectories(staged);
        SyncPaths.resolve(base,rel(base,p));
        BasicFileAttributes before=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!before.isRegularFile() || before.isSymbolicLink())throw new IOException("Unsupported source");
        var beforeStamp=ScanCache.stamp(p,before);
        int permits=before.size()<ContentChunker.MIN?1:17;
        if(memory!=null)try {while(!memory.tryAcquire(permits,100,TimeUnit.MILLISECONDS))checkCancelled(cancelled);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new ScanCancelled();}
        try {
        var md=Hashing.newSha256();List<ChunkRef> refs;
        ContentChunker.Sink sink=(ref,data)-> {
            checkCancelled(cancelled);
            if(staged==null || (requested!=null&&!requested.contains(ref.hash())))return;
            Path chunk=staged.resolve(ref.hash());
            if(!Files.isRegularFile(chunk,LinkOption.NOFOLLOW_LINKS) || Files.size(chunk)!=ref.size() || !Hashing.sha256Hex(chunk).equals(ref.hash()))
                TransferEngine.writeStaged(staged,ref.hash(),data);
        };
        try(InputStream raw=Files.newInputStream(p,LinkOption.NOFOLLOW_LINKS);
            InputStream in=new java.security.DigestInputStream(new FilterInputStream(raw) {
                @Override public int read(byte[] b,int off,int len)throws IOException {checkCancelled(cancelled);int n=super.read(b,off,len);if(n>0)bytesRead.accept(n);return n;}
                @Override public int read()throws IOException {checkCancelled(cancelled);int n=super.read();if(n>=0)bytesRead.accept(1);return n;}
            },md)) {
            if(before.size()<ContentChunker.MIN) {
                byte[] data=in.readNBytes(ContentChunker.MIN);
                if(in.read()!=-1)throw new SourceChanged(rel(base,p));
                ChunkRef ref=new ChunkRef(Hashing.hex(md.digest()),data.length);sink.accept(ref,data);refs=List.of(ref);
            } else refs=ContentChunker.split(in,sink);
        }
        var after=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        long size=0;for(var ref:refs)size+=ref.size();
        if(size!=before.size() || !beforeStamp.equals(ScanCache.stamp(p,after)))
            throw new SourceChanged(rel(base,p));
        return new HashedFile(new ManifestEntry(rel(base,p),size,before.size()<ContentChunker.MIN?refs.getFirst().hash():Hashing.hex(md.digest()),refs),beforeStamp);
        } finally {if(memory!=null)memory.release(permits);}
    }
    /** A cache hit does not imply transient staging exists. Verify or recreate only requested content. */
    public static void ensureStaged(LocalScan scan,Set<String> requested,Path staged,BooleanSupplier cancelled)throws IOException {
        Set<String> missing=new HashSet<>(requested);
        for(var e:scan.manifest().entries()) {
            boolean rebuild=false;
            for(var ref:e.objects()) if(missing.contains(ref.hash())) {
                checkCancelled(cancelled);Path p=staged.resolve(ref.hash());
                if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS) && Files.size(p)==ref.size() && Hashing.sha256Hex(p).equals(ref.hash()))missing.remove(ref.hash());
                else rebuild=true;
            }
            if(rebuild) {
                Path source=SyncPaths.resolve(scan.flavorPath(),e.path());
                ManifestEntry actual=hashContent(scan.flavorPath(),source,staged,cancelled,ignored->{},null,missing).manifest();
                if(!actual.equals(e))throw new IOException("Source changed since review: "+e.path());
                for(var ref:e.objects())missing.remove(ref.hash());
            }
        }
        if(!missing.isEmpty())throw new IOException("Requested content is absent from the reviewed manifest");
    }
    static String rel(Path root,Path p) { return root.relativize(p).toString().replace('\\','/'); }
    private Scanner() {}
}
