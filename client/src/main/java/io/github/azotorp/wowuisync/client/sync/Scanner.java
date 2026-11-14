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

/** Fail-closed metadata scan with persistent chunk manifests; strict scans verify content. */
public final class Scanner {
    /** During discovery, total is zero and done is the negative discovered count. */
    public interface Progress {
        void update(int done,int total);
        default void phase(String phase) {}
        default void statistics(Statistics statistics) {}
    }
    public record Statistics(int discovered,int cacheHits,int hashedFiles,long bytesHashed,long discoveryNanos,
            long cacheNanos,long metadataNanos,long hashingNanos,long manifestNanos,long persistenceNanos,long elapsedNanos) {}
    private record Found(Path path, BasicFileAttributes attrs) {}
    private static void phase(Progress p,String text) { if(p!=null)p.phase(text); }
    private static final class ScanCancelled extends IOException {}
    private static void checkCancelled(BooleanSupplier cancelled) throws ScanCancelled {
        if(cancelled!=null && cancelled.getAsBoolean()) throw new ScanCancelled();
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
    private static LocalScan scan(Path flavor,Exclusions ex,ClientDb db,Progress progress,BooleanSupplier cancelled,boolean verify)
            throws IOException,SQLException,CancelledException {
        long start=System.nanoTime();
        try {
            phase(progress,"Discovering local files");
            if(progress!=null) progress.update(0,0);
            checkCancelled(cancelled);
            Path base=flavor.toAbsolutePath().normalize();
            if(!Files.isDirectory(base)) throw new IOException("Installation is missing or unreadable");
            List<Found> files=new ArrayList<>();
            Path wtf=SyncPaths.resolve(base,"WTF/.wusprobe"), addons=SyncPaths.resolve(base,"Interface/AddOns/.probe/.probe");
            walk(base,wtf.getParent(),ex,files,progress,cancelled);
            Path ad=addons.getParent().getParent();
            if(Files.exists(ad,LinkOption.NOFOLLOW_LINKS)) {
                try(var s=Files.list(ad)) { for(Path p:(Iterable<Path>)s::iterator) {
                    checkCancelled(cancelled);
                    if(ex.isExcluded(rel(base,p)+"/x")) continue;
                    if(Files.isSymbolicLink(p)) throw new IOException("Linked addon directory: "+p);
                    if(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)) walk(base,p,ex,files,progress,cancelled);
                }}
            }
            long discoveredAt=System.nanoTime();
            phase(progress,"Loading cached file metadata");
            Map<String,ScanCache.Entry> cache=db.scanCache(base.toString());
            Map<String,ScanCache.Entry> rows=new HashMap<>();
            long loadedAt=System.nanoTime();
            if(progress!=null) progress.update(0,files.size());
            List<ManifestEntry> entries=new ArrayList<>();Map<String,Path> byKey=new HashMap<>();
            Path staged=db.staging();int done=0,hits=0,hashed=0;long bytes=0,metadataNanos=0,hashingNanos=0;
            long now=System.currentTimeMillis();
            phase(progress,"Comparing metadata and hashing changed files");
            long report=0;
            for(Found found:files) {
                checkCancelled(cancelled);
                Path p=found.path();String rel=rel(base,p);
                long metadataStart=System.nanoTime();
                ScanCache.Stamp stamp=ScanCache.stamp(p,found.attrs());
                metadataNanos+=System.nanoTime()-metadataStart;
                ScanCache.Entry c=cache.get(rel);ManifestEntry entry;
                if(!verify && stamp.reusable(now) && c!=null && stamp.equals(c.stamp())) {
                    entry=c.manifest();hits++;
                } else {
                    // Verify all ancestors before opening content, including Windows junctions.
                    SyncPaths.resolve(base,rel);
                    long hashStart=System.nanoTime();
                    entry=hashFile(base,p,staged,cancelled);hashed++;bytes+=entry.size();
                    hashingNanos+=System.nanoTime()-hashStart;
                    var after=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    if(!stamp.equals(ScanCache.stamp(p,after)))throw new IOException("File changed while scanning: "+rel);
                }
                entries.add(entry);byKey.put(SyncPaths.key(rel),p);rows.put(rel,new ScanCache.Entry(stamp,entry));done++;
                long tick=System.nanoTime();
                if(progress!=null && (done==files.size() || tick-report>100_000_000L)) {
                    progress.update(done,files.size());
                    progress.statistics(new Statistics(files.size(),hits,hashed,bytes,discoveredAt-start,loadedAt-discoveredAt,metadataNanos,hashingNanos,0,0,tick-start));report=tick;
                }
            }
            long manifestStart=System.nanoTime();
            phase(progress,"Building local manifest");
            var collisions=SyncPaths.caseCollisions(entries.stream().map(ManifestEntry::path).toList());
            if(!collisions.isEmpty()) throw new IOException("Paths collide on Windows: "+collisions.getFirst());
            checkCancelled(cancelled);
            Manifest manifest=new Manifest(entries);
            long hashedAt=System.nanoTime();
            phase(progress,"Saving changed cache entries");
            db.saveScanCache(base.toString(),rows,cache);
            long end=System.nanoTime();
            var stats=new Statistics(files.size(),hits,hashed,bytes,discoveredAt-start,loadedAt-discoveredAt,metadataNanos,hashingNanos,hashedAt-manifestStart,end-hashedAt,end-start);
            if(progress!=null)progress.statistics(stats);
            io.github.azotorp.wowuisync.client.util.ClientLog.LOG.info("Scan "+stats+" verified="+verify);
            return new LocalScan(base,manifest,byKey,List.of(),collisions);
        } catch(ScanCancelled e) { throw new CancelledException(); }
    }
    private static void walk(Path base,Path dir,Exclusions ex,List<Found> out,Progress progress,BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        try { Files.readAttributes(dir,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS); }
        catch(NoSuchFileException missing) { return; }
        Files.walkFileTree(dir,new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path p,BasicFileAttributes a) throws IOException {
                checkCancelled(cancelled);
                String r=rel(base,p);
                if(ex.isExcluded(r+"/x") && ex.isExcluded(r+"/x/y")) return FileVisitResult.SKIP_SUBTREE;
                if(Files.isSymbolicLink(p) || a.isOther()) throw new IOException("Linked directory");
                // Validate each directory once, rather than every ancestor for every cache hit.
                if(!p.toRealPath().equals(p.toAbsolutePath().normalize().toRealPath(LinkOption.NOFOLLOW_LINKS)))
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
                if(progress!=null && out.size()%100==0) progress.update(-out.size(),0);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path p,IOException error) throws IOException { throw error; }
        });
    }
    /** Read changed content once; stage immutable chunks for subsequent missing-object requests. */
    static ManifestEntry hashFile(Path base,Path p,Path staged,BooleanSupplier cancelled) throws IOException {
        Files.createDirectories(staged);
        BasicFileAttributes before=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!before.isRegularFile() || before.isSymbolicLink())throw new IOException("Unsupported source");
        var beforeStamp=ScanCache.stamp(p,before);
        var md=Hashing.newSha256();List<ChunkRef> refs;
        ContentChunker.Sink sink=(ref,data)-> {
            checkCancelled(cancelled);
            Path chunk=staged.resolve(ref.hash());
            if(!Files.isRegularFile(chunk,LinkOption.NOFOLLOW_LINKS) || Files.size(chunk)!=ref.size() || !Hashing.sha256Hex(chunk).equals(ref.hash()))
                TransferEngine.writeStaged(staged,ref.hash(),data);
        };
        try(InputStream raw=Files.newInputStream(p,LinkOption.NOFOLLOW_LINKS);
            InputStream in=new java.security.DigestInputStream(new FilterInputStream(raw) {
                @Override public int read(byte[] b,int off,int len)throws IOException {checkCancelled(cancelled);return super.read(b,off,len);}
                @Override public int read()throws IOException {checkCancelled(cancelled);return super.read();}
            },md)) {
            if(before.size()<ContentChunker.MIN) {
                byte[] data=in.readNBytes(ContentChunker.MIN);
                if(in.read()!=-1)throw new IOException("Source changed during scan");
                ChunkRef ref=new ChunkRef(Hashing.sha256Hex(data),data.length);sink.accept(ref,data);refs=List.of(ref);
            } else refs=ContentChunker.split(in,sink);
        }
        var after=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        long size=0;for(var ref:refs)size+=ref.size();
        if(size!=before.size() || !beforeStamp.equals(ScanCache.stamp(p,after)))
            throw new IOException("File changed while scanning: "+rel(base,p));
        return new ManifestEntry(rel(base,p),size,Hashing.hex(md.digest()),refs);
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
                ManifestEntry actual=hashFile(scan.flavorPath(),source,staged,cancelled);
                if(!actual.equals(e))throw new IOException("Source changed since review: "+e.path());
                for(var ref:e.objects())missing.remove(ref.hash());
            }
        }
        if(!missing.isEmpty())throw new IOException("Requested content is absent from the reviewed manifest");
    }
    static String rel(Path root,Path p) { return root.relativize(p).toString().replace('\\','/'); }
    private Scanner() {}
}
