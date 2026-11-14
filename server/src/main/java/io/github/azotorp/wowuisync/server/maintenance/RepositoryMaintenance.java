package io.github.azotorp.wowuisync.server.maintenance;

import io.github.azotorp.wowuisync.common.*;
import io.github.azotorp.wowuisync.common.chunk.ContentChunker;
import io.github.azotorp.wowuisync.common.hash.Hashing;
import io.github.azotorp.wowuisync.common.json.Json;
import io.github.azotorp.wowuisync.common.manifest.ManifestIO;
import io.github.azotorp.wowuisync.server.config.*;
import io.github.azotorp.wowuisync.server.db.ServerDb;
import io.github.azotorp.wowuisync.server.security.*;
import io.github.azotorp.wowuisync.server.sync.Storage;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Explicit offline migration. Existing content and legacy manifests are preserved. */
public final class RepositoryMaintenance {
    public record Format(int version,String chunker,String appVersion) {}
    public static boolean needsMigration(DataLayout l) throws Exception {
        Storage s=new Storage(l);
        for(String a:accounts(l)) for(String p:s.profiles(a)) if(Files.exists(l.currentManifest(a,p)) && s.headId(a,p)==null)return true;
        if(Files.exists(l.db())) try(var c=DriverManager.getConnection("jdbc:sqlite:file:"+l.db().toAbsolutePath()+"?mode=ro");var stmt=c.createStatement();var r=stmt.executeQuery("SELECT MAX(version) FROM schema_version")) {
            if(r.next()&&r.getInt(1)<ServerDb.SCHEMA_VERSION)return true;
        }
        return false;
    }
    private static List<String> accounts(DataLayout l) throws Exception {
        if(!Files.isDirectory(l.accounts()))return List.of();
        try(var s=Files.list(l.accounts())){return s.filter(Files::isDirectory).map(p->p.getFileName().toString()).toList();}
    }
    public static void markFormat(DataLayout l) throws Exception {
        if(Files.exists(l.root().resolve("repository-v2.json")))return;
        ManifestIO.write(l.root().resolve("repository-v2.json"),Json.toBytes(new Format(2,ContentChunker.ALGORITHM,Version.APP_VERSION)));
    }
    public static void requireSupported(DataLayout l) throws Exception {
        Path f=l.root().resolve("repository-v2.json");
        if(Files.exists(f)){Format format=Json.fromBytes(Files.readAllBytes(f),Format.class);
            if(format.version()!=2 || !ContentChunker.ALGORITHM.equals(format.chunker()))throw new java.io.IOException("Unsupported repository format");}
        // Called while holding the server data lock, before listener startup.
        if(Files.exists(l.db())) try(var c=DriverManager.getConnection("jdbc:sqlite:file:"+l.db().toAbsolutePath()+"?mode=ro");var st=c.createStatement();var r=st.executeQuery("SELECT MAX(version) FROM schema_version")) {
            int v=r.next()?r.getInt(1):0;
            if(v>=1 && v<=ServerDb.SCHEMA_VERSION)ServerDb.validateSchema(c,v);
            if(v<1 || v>ServerDb.SCHEMA_VERSION)throw new java.io.IOException("Unrecognized database schema "+v+". Use a compatible server JAR; data was not migrated.");
        }
        if(needsMigration(l)) {
            try { migrateLocked(l); }
            catch(Exception e) { throw new java.io.IOException("Automatic migration failed; server was not started. Preserve data and migration-checkpoints; see migration diagnostics and restore the verified pre-migration checkpoint offline with the matching JAR.",e); }
        }
    }
    public static Path migrate(DataLayout l) throws Exception {
        try(var lock=DataLock.acquire(l.root())) { return migrateLocked(l); }
    }
    static Path migrateLocked(DataLayout l) throws Exception {
            Path formatFile=l.root().resolve("repository-v2.json");
            if(Files.exists(formatFile)) {
                Format format=Json.fromBytes(Files.readAllBytes(formatFile),Format.class);
                if(format.version()!=2 || !ContentChunker.ALGORITHM.equals(format.chunker()))throw new java.io.IOException("Unsupported repository format; data was not migrated");
            }
            if(!Files.exists(l.db()) || !Files.exists(l.config()) || !Files.exists(l.keystore()))throw new java.io.IOException("Initialized database/config/TLS identity required");
            try(var c=DriverManager.getConnection("jdbc:sqlite:file:"+l.db().toAbsolutePath()+"?mode=ro");var st=c.createStatement();var r=st.executeQuery("SELECT MAX(version) FROM schema_version")) {
                ServerDb.validateSchema(c,r.next()?r.getInt(1):0);
            }
            if(Files.getFileStore(l.root()).getUsableSpace()<Files.size(l.db())*2+(256L<<20))throw new java.io.IOException("Insufficient migration checkpoint space");
            Path checkpoints=l.root().resolve("migration-checkpoints");Files.createDirectories(checkpoints);
            Path checkpoint=checkpoints.resolve("v1-"+System.currentTimeMillis());Files.createDirectories(checkpoint);
            // Database backup via SQLite, before opening the new schema.
            if(Files.exists(l.db()))try(var c=DriverManager.getConnection("jdbc:sqlite:"+l.db().toAbsolutePath());var stmt=c.createStatement()) {
                stmt.execute("VACUUM INTO '"+checkpoint.resolve("wowuisync.db").toString().replace("'","''")+"'");
            }
            copy(l.root().resolve("repository-v2.json"),checkpoint.resolve("repository-v2.json"));
            copy(l.config(),checkpoint.resolve("server.json"));copyTree(l.keys(),checkpoint.resolve("keys"));copyTree(l.tls(),checkpoint.resolve("tls"));
            Storage storage=new Storage(l);List<String> report=new ArrayList<>();Map<String,String> hashes=new TreeMap<>();
            for(String a:accounts(l))for(String p:storage.profiles(a)) {
                Path metadata=checkpoint.resolve("profiles").resolve(a).resolve(p);
                copy(l.currentManifest(a,p),metadata.resolve("current.manifest"));
                copy(l.head(a,p),metadata.resolve("head-v2"));
                copyTree(l.snapshots(a,p),metadata.resolve("snapshots-v2"));
            }
            try(var files=Files.walk(checkpoint)){for(Path file:(Iterable<Path>)files.filter(Files::isRegularFile)::iterator)hashes.put(checkpoint.relativize(file).toString().replace('\\','/'),Hashing.sha256Hex(file));}
            ManifestIO.write(checkpoint.resolve("checksums.json"),Json.toBytes(hashes));
            for(var entry:hashes.entrySet())if(!Hashing.sha256Hex(checkpoint.resolve(entry.getKey())).equals(entry.getValue()))throw new java.io.IOException("Checkpoint verification failed");
            for(String a:accounts(l))for(String p:storage.profiles(a)) {
                if(storage.headId(a,p)!=null){report.add(a+"/"+p+": already converted");continue;}
                var m=storage.readManifest(a,p);if(m==null)continue;
                storage.verify(a,p,m.manifest());
                var snap=storage.commitSnapshot(a,p,m,"migration_baseline",null,true);report.add(a+"/"+p+": "+snap.id()+" files="+m.manifest().size());
            }
            try(ServerDb db=ServerDb.open(l.db(),l.dbBackups())){db.integrity();}
            markFormat(l);Permissions.lockDown(l,true);
            String text="Migration "+Version.APP_VERSION+"\nUTC: "+java.time.Instant.now()+"\nCheckpoint: "+checkpoint+"\nTLS fingerprint: "+TlsKeys.fingerprint(TlsKeys.load(l.keystore()))+"\n"+String.join("\n",report)+"\nLegacy objects and manifests retained. No WoW files changed.\n";
            ManifestIO.write(l.root().resolve("migration-report.txt"),text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.out.println(text);return checkpoint;
    }
    private static void copy(Path from,Path to) throws Exception{if(Files.exists(from)){Files.createDirectories(to.getParent());Files.copy(from,to);}}
    private static void copyTree(Path from,Path to) throws Exception{if(!Files.exists(from))return;try(var s=Files.walk(from)){for(Path p:(Iterable<Path>)s::iterator){Path target=to.resolve(from.relativize(p));if(Files.isDirectory(p))Files.createDirectories(target);else copy(p,target);}}}
    public static void verify(DataLayout l) throws Exception {
        try(var c=DriverManager.getConnection("jdbc:sqlite:file:"+l.db().toAbsolutePath()+"?mode=ro");var stmt=c.createStatement();var r=stmt.executeQuery("PRAGMA integrity_check")) {
            if(!r.next()||!"ok".equals(r.getString(1)))throw new java.io.IOException("SQLite integrity failed");
        }
        Storage storage=new Storage(l);int count=0;
        for(String a:accounts(l))for(String p:storage.profiles(a)) {
            var current=storage.readManifest(a,p);if(current!=null)storage.verify(a,p,current.manifest());
            for(var snap:storage.snapshots(a,p)){storage.verify(a,p,snap.content().manifest());count++;}
        }
        System.out.println("Verified "+count+" retained snapshots; schema="+ServerDb.SCHEMA_VERSION+" repository=2; TLS="+TlsKeys.fingerprint(TlsKeys.load(l.keystore())));
    }
    public static void gc(DataLayout l) throws Exception {
        try(var lock=DataLock.acquire(l.root())) {Storage storage=new Storage(l);for(String a:accounts(l))System.out.println(a+": reclaimed "+storage.collect(a,48L*3600*1000)+" bytes");}
    }
    public static void status(DataLayout l) throws Exception {
        System.out.println("state="+(DataLock.running(l.root())?"running":"stopped"));System.out.println("version="+Version.APP_VERSION+" protocol="+Version.PROTOCOL_VERSION+" schema="+ServerDb.SCHEMA_VERSION+" repository=2");System.out.println("data="+l.root());
    }
    private RepositoryMaintenance(){}
}
