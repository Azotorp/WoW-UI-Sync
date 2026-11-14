package io.github.azotorp.wowuisync.client.sync;

import io.github.azotorp.wowuisync.common.hash.Hashing;
import io.github.azotorp.wowuisync.common.json.Json;
import io.github.azotorp.wowuisync.common.manifest.*;
import io.github.azotorp.wowuisync.common.path.SyncPaths;
import java.io.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;

/** Durable idempotent apply journal. No live file is used as an apply input. */
public final class ApplyJournal {
    public record Operation(String id,String installationPath,String profileId,String hostPort,String sourceAccountId,
        String recoveryAccountId,String recoverySnapshotId,List<ManifestEntry> before,List<ManifestEntry> target,boolean full) {}
    public static Path journal(Path data,Path installation) {
        String key=installation.toAbsolutePath().normalize().toString();
        return data.resolve("operations").resolve(Hashing.sha256Hex(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))+".json");
    }
    public static Operation read(Path file) throws IOException { return Json.fromBytes(Files.readAllBytes(file),Operation.class); }
    public static void write(Path file,Operation op) throws IOException { Files.createDirectories(file.getParent());ManifestIO.write(file,Json.toBytes(op)); }
    public static Path prepared(Path data,Operation op) { return data.resolve("operations").resolve(op.id()).resolve("files"); }
    public static void reconstruct(Path chunks,Path prepared,Collection<ManifestEntry> files) throws IOException {
        Files.createDirectories(prepared);
        for(ManifestEntry e:files) {
            Path target=prepared.resolve(e.sha256());
            if(Files.exists(target) && Files.size(target)==e.size() && Hashing.sha256Hex(target).equals(e.sha256())) continue;
            Path tmp=prepared.resolve(UUID.randomUUID()+".tmp");var md=Hashing.newSha256();long total=0;
            try(OutputStream out=new java.security.DigestOutputStream(Files.newOutputStream(tmp),md)) {
                for(var ref:e.objects()) {
                    Path file=chunks.resolve(ref.hash());
                    if(!Files.isRegularFile(file) || Files.size(file)!=ref.size() || !Hashing.sha256Hex(file).equals(ref.hash())) throw new IOException("Chunk verification failed");
                    try(InputStream in=Files.newInputStream(file)) { total+=in.transferTo(out); }
                }
            } catch(Exception x) { Files.deleteIfExists(tmp);throw x; }
            if(total!=e.size() || !Hashing.hex(md.digest()).equals(e.sha256())) { Files.deleteIfExists(tmp);throw new IOException("Reconstruction failed"); }
            try(var ch=FileChannel.open(tmp,StandardOpenOption.WRITE)) { ch.force(true); }
            TransferEngine.moveInto(tmp,target);
        }
    }
    private static boolean same(Path file,ManifestEntry e) throws IOException {
        return e!=null && Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) && Files.size(file)==e.size() && Hashing.sha256Hex(file).equals(e.sha256());
    }
    public static void apply(Path data,Path journal) throws IOException {
        Operation op=read(journal);Path root=Path.of(op.installationPath());
        Manifest before=new Manifest(op.before()),target=new Manifest(op.target());ManifestDiff diff=ManifestDiff.between(before,target);
        for(var entry:target.entries())SyncPaths.resolve(root,entry.path());
        List<ManifestEntry> writes=new ArrayList<>(diff.added());writes.addAll(diff.edited());
        // Verify the complete apply inputs before the first mutation, including during replay.
        for(var e:writes) {
            Path staged=prepared(data,op).resolve(e.sha256());
            if(!same(staged,e)) throw new IOException("Prepared content is missing or corrupt. Reconnect to recover.");
            Path destination=SyncPaths.resolve(root,e.path());
            if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS) && !same(destination,before.get(e.path())) && !same(destination,e))
                throw new IOException("Destination changed since review: "+e.path());
        }
        for(var e:diff.removed()) {
            Path file=SyncPaths.resolve(root,e.path());
            if(Files.exists(file,LinkOption.NOFOLLOW_LINKS) && !same(file,e)) throw new IOException("Destination changed: "+e.path());
        }
        for(var e:writes) {
            Path targetFile=SyncPaths.resolve(root,e.path());
            if(same(targetFile,e)) continue;
            Files.createDirectories(targetFile.getParent());SyncPaths.resolve(root,e.path());
            Path tmp=targetFile.resolveSibling(targetFile.getFileName()+".wus-"+op.id()+".tmp");
            Files.copy(prepared(data,op).resolve(e.sha256()),tmp,StandardCopyOption.REPLACE_EXISTING);
            if(!same(tmp,e)) throw new IOException("Prepared file changed");
            try(var ch=FileChannel.open(tmp,StandardOpenOption.WRITE)) { ch.force(true); }
            TransferEngine.moveInto(tmp,targetFile);
            ManifestEntry old=before.get(e.path());
            if(old!=null && !old.path().equals(e.path())) {
                Path oldFile=SyncPaths.resolve(root,old.path());
                if(Files.exists(oldFile) && !Files.isSameFile(oldFile,targetFile)) {
                    if(!same(oldFile,old)) throw new IOException("Case-rename source changed");Files.delete(oldFile);
                }
            }
        }
        for(var e:diff.removed()) {
            Path file=SyncPaths.resolve(root,e.path());
            if(Files.exists(file) && !same(file,e)) throw new IOException("Deletion source changed");
            Files.deleteIfExists(file);
            Path stop=SyncPaths.addonFolder(e.path())==null?root.resolve("WTF"):root.resolve("Interface/AddOns");
            Path parent=file.getParent();
            while(parent!=null && parent.startsWith(stop) && !parent.equals(stop)) {
                try { Files.delete(parent); } catch(DirectoryNotEmptyException x) { break; }parent=parent.getParent();
            }
        }
    }
    public static void finish(Path data,Path journal) throws IOException {
        Operation op=read(journal);Files.delete(journal);
        Path dir=prepared(data,op).getParent();if(Files.exists(dir)) try(var s=Files.walk(dir)) {
            for(Path p:(Iterable<Path>)s.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(p);
        }
    }
    private ApplyJournal() {}
}
