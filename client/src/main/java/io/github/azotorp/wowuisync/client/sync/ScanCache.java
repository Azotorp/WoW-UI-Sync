package io.github.azotorp.wowuisync.client.sync;

import io.github.azotorp.wowuisync.common.hash.Hashing;
import io.github.azotorp.wowuisync.common.manifest.ManifestEntry;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** Versioned cache hints, never proof of transferred content. Old millisecond-only hashes are not promoted. */
public final class ScanCache {
    private static final boolean WINDOWS=System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    public record Stamp(long size,long modified,long created,String identity,Long changed) {
        public boolean reusable(long now) {
            // A recent/coarse timestamp can hide rapid writes. Unknown file identity is untrusted.
            return identity!=null && modified/1_000_000L < now-2000;
        }
    }
    public record Entry(Stamp stamp, ManifestEntry manifest) {}
    public static Stamp stamp(Path p, BasicFileAttributes a) throws IOException {
        Long changed = null;
        try { if(p.getFileSystem().supportedFileAttributeViews().contains("unix")) changed = ((java.nio.file.attribute.FileTime) Files.getAttribute(p,"unix:ctime",LinkOption.NOFOLLOW_LINKS)).to(java.util.concurrent.TimeUnit.NANOSECONDS); }
        catch (UnsupportedOperationException | IllegalArgumentException ignored) { /* Windows: use identity and precise times. */ }
        return new Stamp(a.size(), a.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS), a.creationTime().to(java.util.concurrent.TimeUnit.NANOSECONDS),
                identity(a,WINDOWS),changed);
    }
    static String identity(BasicFileAttributes a,boolean windows) {
        if(a.fileKey()!=null)return a.fileKey().toString();
        // OpenJDK's Windows provider returns null for fileKey. Creation time provides
        // a replacement hint; strict transfer/apply scans still verify every byte.
        long created=a.creationTime().to(java.util.concurrent.TimeUnit.NANOSECONDS);
        return windows && created>0 ? "windows-created:"+created : null;
    }
    private static String text(String s) { return java.util.Base64.getEncoder().encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static String untext(String s) { return new String(java.util.Base64.getDecoder().decode(s),java.nio.charset.StandardCharsets.UTF_8); }
    /** Compact, explicitly versioned primitives avoid JSON mapper work on thousands of small files. */
    public static String encode(Entry e) {
        Stamp s=e.stamp();StringBuilder refs=new StringBuilder();
        for(var c:e.manifest().objects()) { if(!refs.isEmpty())refs.append(';');refs.append(c.hash()).append(':').append(c.size()); }
        return String.join("\n","scan-cache-1",text(e.manifest().path()),Long.toString(s.size()),Long.toString(s.modified()),
                Long.toString(s.created()),s.identity()==null?"-":text(s.identity()),s.changed()==null?"-":s.changed().toString(),e.manifest().sha256(),refs.toString());
    }
    public static Entry decode(String payload, String checksum) {
        try {
            if(!Hashing.sha256Hex(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(checksum))return null;
            String[] v=payload.split("\n",-1);if(v.length!=9 || !v[0].equals("scan-cache-1"))return null;
            String path=untext(v[1]);long size=Long.parseLong(v[2]);
            if(size<0 || !io.github.azotorp.wowuisync.common.path.SyncPaths.isSafeRelative(path) || !Hashing.isSha256Hex(v[7]))return null;
            var refs=new java.util.ArrayList<io.github.azotorp.wowuisync.common.chunk.ChunkRef>();long total=0;
            for(String r:v[8].split(";")) {
                int sep=r.indexOf(':');if(sep!=64)return null;String hash=r.substring(0,sep);long n=Long.parseLong(r.substring(sep+1));
                if(!Hashing.isSha256Hex(hash)||n<0||n>io.github.azotorp.wowuisync.common.chunk.ContentChunker.MAX)return null;
                total=Math.addExact(total,n);refs.add(new io.github.azotorp.wowuisync.common.chunk.ChunkRef(hash,n));
            }
            if(total!=size)return null;
            var stamp=new Stamp(size,Long.parseLong(v[3]),Long.parseLong(v[4]),v[5].equals("-")?null:untext(v[5]),v[6].equals("-")?null:Long.valueOf(v[6]));
            return new Entry(stamp,new ManifestEntry(path,size,v[7],refs));
        } catch(Exception corrupt) { return null; }
    }
    private ScanCache() {}
}
