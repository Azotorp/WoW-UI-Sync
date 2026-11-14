package io.github.azotorp.wowuisync.server.sync;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.azotorp.wowuisync.common.*;
import io.github.azotorp.wowuisync.common.chunk.*;
import io.github.azotorp.wowuisync.common.codec.Codec;
import io.github.azotorp.wowuisync.common.hash.Hashing;
import io.github.azotorp.wowuisync.common.json.Json;
import io.github.azotorp.wowuisync.common.manifest.*;
import io.github.azotorp.wowuisync.common.object.ObjectFiles;
import io.github.azotorp.wowuisync.common.protocol.Msg;
import io.github.azotorp.wowuisync.server.config.DataLayout;
import java.io.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Repository v2. Immutable manifests are durable before the atomic authoritative head pointer. */
public class Storage {
    public record StoredManifest(@JsonProperty("fingerprint") String fingerprint,
            @JsonProperty("entries") List<ManifestEntry> entries, @JsonProperty("lastPushAt") Long lastPushAt,
            @JsonProperty("lastPushDevice") String lastPushDevice,@JsonProperty("lastPushBy") String lastPushBy) {
        public Manifest manifest() { return new Manifest(entries==null ? List.of() : entries); }
    }
    public record Snapshot(String id,String parentId,String profileId,long createdAt,String device,String sourceType,
            String installationId,StoredManifest content,long physicalBytes) {}
    public record Annotation(String label,boolean pinned) {}
    protected final DataLayout layout;
    public final Object transactionLock = new Object();
    private final Set<String> locked=ConcurrentHashMap.newKeySet();
    private final Map<String,AtomicInteger> transfers=new ConcurrentHashMap<>();
    private final Map<String,Object> locks=new ConcurrentHashMap<>();
    private final Map<String,Long> displayUsage=new ConcurrentHashMap<>();
    private record Head(String id,StoredManifest manifest) {}
    private final Map<String,Head> displayHeads=new ConcurrentHashMap<>();
    /** Rebuild display metadata before accepting connections, never in a status request. */
    public void warmStatusMetadata() throws IOException {
        if(!Files.isDirectory(layout.accounts()))return;
        try(var dirs=Files.list(layout.accounts())) {
            for(Path dir:(Iterable<Path>)dirs.filter(Files::isDirectory)::iterator) {
                String a=dir.getFileName().toString();displayUsage.put(a,logicalBytes(a));
                for(String p:profiles(a)) statusManifest(a,p);
            }
        }
    }
    public long statusUsage(String a) { return displayUsage.getOrDefault(a,0L); }
    public StoredManifest statusManifest(String a,String p) throws IOException {
        String id=headId(a,p);Head old=displayHeads.get(a+"/"+p);
        if(old!=null && Objects.equals(id,old.id()))return old.manifest();
        StoredManifest m=readManifest(a,p);
        if(m!=null)displayHeads.put(a+"/"+p,new Head(id,m));else displayHeads.remove(a+"/"+p);
        return m;
    }
    public Storage(DataLayout layout) { this.layout=layout; }
    public synchronized boolean tryLockAccount(String a) {
        if(transfers.entrySet().stream().anyMatch(e->e.getKey().startsWith(a+"/") && e.getValue().get()>0)) return false;
        return locked.add(a);
    }
    public synchronized void unlockAccount(String a) { locked.remove(a); }
    public synchronized boolean beginTransfer(String a,String p) {
        if(locked.contains(a)) return false;
        transfers.computeIfAbsent(a+"/"+p,k->new AtomicInteger()).incrementAndGet();return true;
    }
    public synchronized void endTransfer(String a,String p) {
        var c=transfers.get(a+"/"+p); if(c!=null && c.decrementAndGet()<=0) transfers.remove(a+"/"+p);
    }
    public synchronized int activeTransfers(String a,String p) { var c=transfers.get(a+"/"+p);return c==null?0:c.get(); }
    public Object profileLock(String a,String p) { return locks.computeIfAbsent(a+"/"+p,k->new Object()); }
    public Object objectLock(String a,String h) { return locks.computeIfAbsent(a+"/object/"+h,k->new Object()); }
    public String headId(String a,String p) throws IOException {
        Path h=layout.head(a,p);return Files.exists(h)?Files.readString(h).trim():null;
    }
    public StoredManifest readManifest(String a,String p) throws IOException {
        String head=headId(a,p);if(head!=null) return snapshot(a,p,head).content();
        Path legacy=layout.currentManifest(a,p);if(!Files.exists(legacy)) return null;
        return Json.fromBytes(Codec.decodeTagged(Files.readAllBytes(legacy)),StoredManifest.class);
    }
    /** Legacy API retained for migration fixtures. New code commits snapshots. */
    public void writeManifest(String a,String p,StoredManifest m) throws IOException {
        commitSnapshot(a,p,m,"push",null,true);
    }
    public Snapshot commitSnapshot(String a,String p,StoredManifest m,String source,String installation,boolean advance) throws IOException {
        synchronized(transactionLock) {
            if(!m.manifest().fingerprint().equals(m.fingerprint()))throw new IOException("Manifest fingerprint mismatch");
            verify(a,p,m.manifest());
            String id=UUID.randomUUID().toString(); Path dir=layout.snapshots(a,p);Files.createDirectories(dir);
            Snapshot snap=new Snapshot(id,headId(a,p),p,System.currentTimeMillis(),m.lastPushDevice(),source,installation,m,physicalBytes(a));
            byte[] bytes=Json.toBytes(snap);
            ManifestIO.write(dir.resolve(id+".sha256"),Hashing.sha256Hex(bytes).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            ManifestIO.write(dir.resolve(id+".json"),bytes);
            if(advance) ManifestIO.write(layout.head(a,p),id.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            displayUsage.put(a,logicalBytes(a));
            if(advance) displayHeads.put(a+"/"+p,new Head(id,m));
            return snap;
        }
    }
    public Snapshot snapshot(String a,String p,String id) throws IOException {
        if(id==null || !id.matches("[a-f0-9-]{36}")) throw new IOException("Invalid snapshot identity");
        Path file=layout.snapshots(a,p).resolve(id+".json");
        if(Files.exists(file.resolveSibling(id+".deleted")))throw new NoSuchFileException(id);
        byte[] bytes=Files.readAllBytes(file);
        if(!Hashing.sha256Hex(bytes).equals(Files.readString(file.resolveSibling(id+".sha256")).trim())) throw new IOException("Snapshot checksum mismatch");
        Snapshot snap=Json.fromBytes(bytes,Snapshot.class);
        if(!id.equals(snap.id()) || !p.equals(snap.profileId())) throw new IOException("FLAVOR_MISMATCH");
        if(!snap.content().fingerprint().equals(snap.content().manifest().fingerprint())) throw new IOException("Manifest fingerprint mismatch");
        return snap;
    }
    public List<Snapshot> snapshots(String a,String p) throws IOException {
        Path dir=layout.snapshots(a,p);if(!Files.isDirectory(dir)) return List.of();
        List<Snapshot> result=new ArrayList<>();
        try(var s=Files.list(dir)) {
            for(Path file:(Iterable<Path>)s.filter(x->x.toString().endsWith(".json"))::iterator) {
                String id=file.getFileName().toString().replace(".json","");
                if(Files.exists(dir.resolve(id+".deleted"))) continue;
                result.add(snapshot(a,p,id));
            }
        }
        result.sort(Comparator.comparingLong(Snapshot::createdAt).reversed());return result;
    }
    public List<String> profiles(String a) throws IOException {
        Path dir=layout.accountDir(a).resolve("profiles");if(!Files.isDirectory(dir)) return List.of();
        try(var s=Files.list(dir)) { return s.filter(Files::isDirectory).map(x->x.getFileName().toString()).toList(); }
    }
    public Annotation annotation(String a,String p,String id) throws IOException {
        Path f=layout.snapshots(a,p).resolve(id+".meta");return Files.exists(f)?Json.fromBytes(Files.readAllBytes(f),Annotation.class):new Annotation("",false);
    }
    public Msg.SnapshotInfo info(String a,String p,Snapshot s) throws IOException {
        Annotation m=annotation(a,p,s.id());return new Msg.SnapshotInfo(s.id(),s.parentId(),p,s.createdAt(),s.device(),s.sourceType(),
            s.installationId(),s.content().fingerprint(),s.content().manifest().size(),s.content().manifest().totalBytes(),s.physicalBytes(),m.label(),m.pinned(),s.id().equals(headId(a,p)));
    }
    public void retention(String a,String p,int keepLast) throws IOException {
        if(keepLast<0||keepLast>100_000)throw new IOException("Retention count must be 0-100000");
        Path file=layout.profileDir(a,p).resolve("retention.json");Files.createDirectories(file.getParent());ManifestIO.write(file,Json.toBytes(keepLast));prune(a,p);
    }
    public void prune(String a,String p) throws IOException {
        synchronized(transactionLock){
            Path file=layout.profileDir(a,p).resolve("retention.json");if(!Files.exists(file)||activeTransfers(a,p)>0)return;
            int keep=Json.fromBytes(Files.readAllBytes(file),Integer.class);if(keep==0)return;
            var all=snapshots(a,p);
            for(int i=keep;i<all.size();i++) {
                var snap=all.get(i);if(snap.id().equals(headId(a,p))||annotation(a,p,snap.id()).pinned()||System.currentTimeMillis()-snap.createdAt()<48L*3600*1000)continue;
                annotate(a,p,snap.id(),null,null,true);
            }
        }
    }
    public void annotate(String a,String p,String id,String label,Boolean pinned,boolean delete) throws IOException {
        synchronized(transactionLock) {
            snapshot(a,p,id);Annotation old=annotation(a,p,id);
            if(delete) {
                if(old.pinned() || id.equals(headId(a,p)) || activeTransfers(a,p)>0) throw new IOException("Snapshot is pinned, current, or in use");
                ManifestIO.write(layout.snapshots(a,p).resolve(id+".deleted"),new byte[]{1});displayUsage.put(a,logicalBytes(a));return;
            }
            if(label!=null && label.length()>256) throw new IOException("Label too long");
            ManifestIO.write(layout.snapshots(a,p).resolve(id+".meta"),Json.toBytes(new Annotation(label==null?old.label():label,pinned==null?old.pinned():pinned)));
        }
    }
    public Path objectPath(String a,String p,String h) {
        Path modern=ObjectFiles.shardPath(layout.chunks(a),h);
        if(Files.exists(modern)) return modern;
        Path legacy=ObjectFiles.shardPath(layout.objects(a,p),h);return Files.exists(legacy)?legacy:modern;
    }
    public boolean hasObject(String a,String p,String h) { return Files.isRegularFile(objectPath(a,p,h),LinkOption.NOFOLLOW_LINKS); }
    public void storeObject(String a,String p,String h,byte[] data,boolean compress) throws IOException {
        if(!Hashing.sha256Hex(data).equals(h) || data.length>ContentChunker.MAX) throw new IOException("Invalid chunk");
        synchronized(objectLock(a,h)) {
            if(hasObject(a,p,h)) return;
            Path target=ObjectFiles.shardPath(layout.chunks(a),h);Files.createDirectories(layout.tmp());
            Path tmp=layout.tmp().resolve(UUID.randomUUID()+".obj");
            try { ObjectFiles.write(tmp,data,compress?Codec.preferred():Codec.RAW,3);durableMove(tmp,target); }
            finally { Files.deleteIfExists(tmp); }
        }
    }
    public void storeObjectFromFile(String a,String p,String h,Path file,boolean compress) throws IOException {
        if(Files.size(file)>ContentChunker.MAX) throw new IOException("Chunk too large");
        storeObject(a,p,h,Files.readAllBytes(file),compress);
    }
    private static void durableMove(Path tmp,Path target) throws IOException {
        Files.createDirectories(target.getParent());try(var ch=FileChannel.open(tmp,StandardOpenOption.WRITE)) { ch.force(true); }
        try { Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE); }
        catch(AtomicMoveNotSupportedException e) { Files.move(tmp,target); }
        try(var ch=FileChannel.open(target.getParent(),StandardOpenOption.READ)) { ch.force(true); }
        catch(IOException|UnsupportedOperationException ignored) {}
    }
    public void verify(String a,String p,Manifest man) throws IOException {
        Map<String,Long> verified=new HashMap<>();
        for(ManifestEntry e:man.entries()) {
            var md=Hashing.newSha256();long size=0;
            for(ChunkRef ref:e.objects()) {
                Path obj=objectPath(a,p,ref.hash());var cm=Hashing.newSha256();long count=0;
                try(InputStream in=ObjectFiles.openContent(obj)) {
                    byte[] buf=new byte[65536];int n;
                    while((n=in.read(buf))!=-1) {
                        count+=n;if(count>ref.size()) throw new IOException("Object exceeds declared size");
                        md.update(buf,0,n);cm.update(buf,0,n);
                    }
                }
                if(count!=ref.size() || !Hashing.hex(cm.digest()).equals(ref.hash())) throw new IOException("Chunk integrity failed");
                size=Math.addExact(size,count);verified.put(ref.hash(),count);
            }
            if(size!=e.size() || !Hashing.hex(md.digest()).equals(e.sha256())) throw new IOException("File integrity failed: "+e.path());
        }
    }
    public boolean authorizedObject(String a,String p,String hash) throws IOException {
        for(Snapshot s:snapshots(a,p)) if(uniqueHashes(s.content().manifest()).contains(hash)) return true;
        StoredManifest current=readManifest(a,p);return current!=null && uniqueHashes(current.manifest()).contains(hash);
    }
    public Map<String,Long> retainedObjects(String a) throws IOException {
        Map<String,Long> refs=new HashMap<>();
        for(String p:profiles(a)) {
            for(Snapshot s:snapshots(a,p)) addRefs(refs,s.content().manifest());
            StoredManifest cur=readManifest(a,p);if(cur!=null) addRefs(refs,cur.manifest());
        }
        return refs;
    }
    public static void addRefs(Map<String,Long> refs,Manifest m) {
        for(ManifestEntry e:m.entries()) for(ChunkRef r:e.objects()) {
            Long old=refs.putIfAbsent(r.hash(),r.size());if(old!=null && old!=r.size()) throw new IllegalArgumentException("Inconsistent chunk size");
        }
    }
    public Map<String,Long> chargedObjects(String a) throws IOException {
        Map<String,Long> refs=retainedObjects(a);Path account=layout.accountDir(a);
        if(Files.exists(account))try(var stream=Files.walk(account)) {
            for(Path file:(Iterable<Path>)stream.filter(Files::isRegularFile)::iterator) {
                String name=file.getFileName().toString();
                if(Hashing.isSha256Hex(name)) refs.putIfAbsent(name,ObjectFiles.header(file).originalSize());
            }
        }
        Path partial=layout.partial().resolve(a);
        if(Files.exists(partial))try(var stream=Files.list(partial)) {
            for(Path file:(Iterable<Path>)stream.filter(Files::isRegularFile)::iterator) if(Hashing.isSha256Hex(file.getFileName().toString()))
                refs.putIfAbsent(file.getFileName().toString(),Files.size(file));
        }
        return refs;
    }
    public long logicalBytes(String a) throws IOException { return sum(retainedObjects(a)); }
    public static long sum(Map<String,Long> refs) { long n=0;for(long size:refs.values()) n=Math.addExact(n,size);return n; }
    public long physicalBytes(String a) throws IOException {
        Path root=layout.accountDir(a);if(!Files.exists(root)) return 0;
        try(var s=Files.walk(root)) { long total=0;for(Path f:(Iterable<Path>)s.filter(Files::isRegularFile)::iterator) total=Math.addExact(total,Files.size(f));return total; }
    }
    public Path partialPath(String a,String h) {
        if(!Hashing.isSha256Hex(h)) throw new IllegalArgumentException("Invalid hash");return layout.partial().resolve(a).resolve(h);
    }
    public long partialSize(String a,String h) { try { Path f=partialPath(a,h);return Files.exists(f)?Files.size(f):0; } catch(IOException e) { return 0; } }
    public long appendPartial(String a,String h,long offset,byte[] bytes,int off,int len) throws IOException {
        synchronized(objectLock(a,h)) {
            Path file=partialPath(a,h);Files.createDirectories(file.getParent());long size=partialSize(a,h);
            if(offset==0 && size>0) { Files.delete(file);size=0; }
            if(size!=offset) return size;
            try(OutputStream out=Files.newOutputStream(file,StandardOpenOption.CREATE,StandardOpenOption.APPEND)) { out.write(bytes,off,len); }
            return offset+len;
        }
    }
    public String sha256OfFile(Path p) throws IOException { return Hashing.sha256Hex(p); }
    public void deletePartial(String a,String h) throws IOException { Files.deleteIfExists(partialPath(a,h)); }
    public void cleanPartials() {
        synchronized(transactionLock) {
            if(!Files.exists(layout.partial()))return;
            try(var dirs=Files.list(layout.partial())) {
                for(Path account:(Iterable<Path>)dirs.filter(Files::isDirectory)::iterator) {
                    String prefix=account.getFileName()+"/";
                    if(transfers.keySet().stream().anyMatch(k->k.startsWith(prefix)))continue;
                    try(var files=Files.list(account)) {for(Path file:(Iterable<Path>)files.filter(Files::isRegularFile)::iterator)
                        if(System.currentTimeMillis()-Files.getLastModifiedTime(file).toMillis()>Defaults.PARTIAL_MAX_AGE_MS)Files.deleteIfExists(file);}
                }
            }catch(IOException e){io.github.azotorp.wowuisync.server.util.Logs.LOG.warning("Partial cleanup failed");}
        }
    }
    /** Compatibility entry point: never collect from just one current manifest. */
    public long gc(String a,String p,Set<String> keep) throws IOException { return physicalBytes(a); }
    public long collect(String a,long graceMillis) throws IOException {
        synchronized(transactionLock) {
            if(transfers.keySet().stream().anyMatch(k->k.startsWith(a+"/"))) throw new IOException("Account has active leases");
            Set<String> keep=retainedObjects(a).keySet();Path root=layout.chunks(a);long deleted=0;
            if(!Files.exists(root)) return 0;
            try(var s=Files.walk(root)) { for(Path f:(Iterable<Path>)s.filter(Files::isRegularFile)::iterator) {
                if(!keep.contains(f.getFileName().toString()) && System.currentTimeMillis()-Files.getLastModifiedTime(f).toMillis()>graceMillis) {
                    deleted+=Files.size(f);Files.delete(f);
                }
            }}return deleted;
        }
    }
    public long objectBytes(String a,String p,Set<String> hashes) throws IOException {
        long total=0;for(String h:hashes) { Path f=objectPath(a,p,h);if(Files.exists(f)) total+=Files.size(f); }return total;
    }
    public static Set<String> uniqueHashes(Manifest m) { Set<String> refs=new HashSet<>();for(var e:m.entries()) for(var r:e.objects()) refs.add(r.hash());return refs; }
    public void deleteAccountData(String a) throws IOException { displayUsage.remove(a);displayHeads.keySet().removeIf(k->k.startsWith(a+"/")); deleteTree(layout.accountDir(a));deleteTree(layout.partial().resolve(a)); }
    public static void deleteTree(Path root) throws IOException {
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS)) return;
        try(var s=Files.walk(root)) { for(Path f:(Iterable<Path>)s.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(f); }
    }
}
