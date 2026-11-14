package io.github.azotorp.wowuisync.server.maintenance;

import io.github.azotorp.wowuisync.common.*;
import io.github.azotorp.wowuisync.common.hash.Hashing;
import io.github.azotorp.wowuisync.common.json.Json;
import io.github.azotorp.wowuisync.common.manifest.ManifestIO;
import io.github.azotorp.wowuisync.server.config.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.sql.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.*;

/** Local release packaging and verified offline activation. No network or automatic updates. */
public final class ReleaseManager {
    public record Release(String version,int protocol,int schema,int repository,String channel,Map<String,String> files) {}
    public record Activation(String previous,String current,String checkpoint) {}
    private static final Set<String> SCRIPTS=Set.of("setup","update","start","stop","status","rollback","verify","reset-root","common");
    private static boolean allowed(String name) {
        if(Set.of("LICENSE","README.md","CHANGELOG.md","ENGINEERING-REPORT.md","SHA256SUMS","release-manifest.json","client/wow-ui-sync.client.jar","client/README.txt","server/wow-ui-sync.server.jar","server/README.txt","server/wow-ui-sync.service").contains(name))return true;
        if(name.startsWith("server/") && name.indexOf('/',7)<0){String file=name.substring(7);for(String script:SCRIPTS)if(file.equals(script+".sh")||file.equals(script+".bat"))return true;}
        return false;
    }
    private static void jar(Path file,String main) throws Exception {
        try(JarFile jar=new JarFile(file.toFile())){
            var attributes=jar.getManifest().getMainAttributes();
            if(!Version.APP_VERSION.equals(attributes.getValue("Implementation-Version")) || !main.equals(attributes.getValue("Main-Class")))throw new IOException("JAR version or entrypoint mismatch");
        }
    }
    public static Path pack(Path source) throws Exception {
        Path root=source.toAbsolutePath().normalize();Map<String,byte[]> files=new TreeMap<>();
        Path client=root.resolve("dist/wow-ui-sync-client.jar"),server=root.resolve("dist/wow-ui-sync-server.jar");
        jar(client,"io.github.azotorp.wowuisync.client.ClientMain");jar(server,"io.github.azotorp.wowuisync.server.ServerMain");
        files.put("client/wow-ui-sync.client.jar",Files.readAllBytes(client));files.put("server/wow-ui-sync.server.jar",Files.readAllBytes(server));
        for(String name:List.of("LICENSE","README.md","CHANGELOG.md","ENGINEERING-REPORT.md"))files.put(name,Files.readAllBytes(root.resolve(name)));
        files.put("client/README.txt",Files.readAllBytes(root.resolve("docs/CLIENT-1.1.md")));files.put("server/README.txt",(Files.readString(root.resolve("docs/SERVER-1.1.md"))+"\n\n"+Files.readString(root.resolve("docs/UPGRADE-1.1.md"))+"\n\n"+Files.readString(root.resolve("docs/RELEASE-STATUS.md"))).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try(var s=Files.list(root.resolve("packaging/server"))){for(Path p:(Iterable<Path>)s.filter(Files::isRegularFile)::iterator)files.put("server/"+p.getFileName(),Files.readAllBytes(p));}
        Map<String,String> hashes=new TreeMap<>();for(var e:files.entrySet()){if(!allowed(e.getKey()))throw new IOException("Unexpected package file");hashes.put(e.getKey(),Hashing.sha256Hex(e.getValue()));}
        Release release=new Release(Version.APP_VERSION,Version.PROTOCOL_VERSION,io.github.azotorp.wowuisync.server.db.ServerDb.SCHEMA_VERSION,2,"alpha",hashes);
        files.put("release-manifest.json",Json.toBytes(release));
        StringBuilder sums=new StringBuilder();for(var e:files.entrySet())sums.append(Hashing.sha256Hex(e.getValue())).append("  ").append(e.getKey()).append('\n');
        files.put("SHA256SUMS",sums.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path out=root.resolve("dist/releases/wow-ui-sync-"+Version.APP_VERSION+".zip");Files.createDirectories(out.getParent());
        try(var zip=new ZipOutputStream(Files.newOutputStream(out))){zip.setLevel(6);for(var e:files.entrySet()){ZipEntry entry=new ZipEntry(e.getKey());entry.setTime(0);zip.putNextEntry(entry);zip.write(e.getValue());zip.closeEntry();}}
        unixModes(out);
        ManifestIO.write(out.resolveSibling(out.getFileName()+".sha256"),(Hashing.sha256Hex(out)+"  "+out.getFileName()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        inspect(out);packClient(root);System.out.println(out);return out;
    }
    /** Manual client-only replacement package; never accepted by the server activation inspector. */
    public static Path packClient(Path source) throws Exception {
        Path root=source.toAbsolutePath().normalize(),client=root.resolve("dist/wow-ui-sync-client.jar");
        jar(client,"io.github.azotorp.wowuisync.client.ClientMain");
        Map<String,byte[]> files=new TreeMap<>();
        files.put("client/wow-ui-sync.client.jar",Files.readAllBytes(client));
        files.put("client/README.txt",Files.readAllBytes(root.resolve("docs/CLIENT-1.1.md")));
        files.put("LICENSE",Files.readAllBytes(root.resolve("LICENSE")));
        StringBuilder sums=new StringBuilder();
        files.forEach((name,bytes)->sums.append(Hashing.sha256Hex(bytes)).append("  ").append(name).append('\n'));
        files.put("SHA256SUMS",sums.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path out=root.resolve("dist/releases/wow-ui-sync-client-"+Version.APP_VERSION+".zip");Files.createDirectories(out.getParent());
        try(var zip=new ZipOutputStream(Files.newOutputStream(out))){zip.setLevel(6);for(var entry:files.entrySet()){
            ZipEntry member=new ZipEntry(entry.getKey());member.setTime(0);zip.putNextEntry(member);zip.write(entry.getValue());zip.closeEntry();
        }}
        unixModes(out);
        try(var zip=new ZipFile(out.toFile())) {
            if(zip.size()!=files.size())throw new IOException("Client package membership mismatch");
            for(var entry:files.entrySet())try(var in=zip.getInputStream(zip.getEntry(entry.getKey()))) {
                if(!Arrays.equals(entry.getValue(),in.readAllBytes()))throw new IOException("Client package bytes mismatch");
            }
        }
        ManifestIO.write(out.resolveSibling(out.getFileName()+".sha256"),(Hashing.sha256Hex(out)+"  "+out.getFileName()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println(out);return out;
    }
    /** ZIP central-directory permissions, without platform-specific archivers. */
    private static void unixModes(Path archive) throws IOException {
        try(RandomAccessFile file=new RandomAccessFile(archive.toFile(),"rw")) {
            long end=file.length()-22;file.seek(end);
            byte[] eocd=new byte[22];file.readFully(eocd);var last=java.nio.ByteBuffer.wrap(eocd).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if(last.getInt(0)!=0x06054b50)throw new IOException("ZIP end record missing");
            long pos=Integer.toUnsignedLong(last.getInt(16));int count=Short.toUnsignedInt(last.getShort(10));
            for(int i=0;i<count;i++) {
                file.seek(pos);byte[] header=new byte[46];file.readFully(header);var h=java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                if(h.getInt(0)!=0x02014b50)throw new IOException("ZIP directory mismatch");
                int names=Short.toUnsignedInt(h.getShort(28)),extra=Short.toUnsignedInt(h.getShort(30)),comment=Short.toUnsignedInt(h.getShort(32));
                byte[] bytes=new byte[names];file.readFully(bytes);String name=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
                h.putShort(4,(short)((3<<8)|20));h.putInt(38,(name.endsWith(".sh")?0100755:0100644)<<16);
                file.seek(pos);file.write(header);pos+=46L+names+extra+comment;
            }
        }
    }
    private static void verifyJarBytes(byte[] bytes,String version,String main) throws Exception {
        try(var jar=new java.util.jar.JarInputStream(new ByteArrayInputStream(bytes))) {
            var manifest=jar.getManifest();
            if(manifest==null || !version.equals(manifest.getMainAttributes().getValue("Implementation-Version")) || !main.equals(manifest.getMainAttributes().getValue("Main-Class")))throw new IOException("Release JAR metadata mismatch");
        }
    }
    public static Map<String,byte[]> inspect(Path archive) throws Exception {
        Map<String,byte[]> files=new TreeMap<>();long total=0;
        try(var zip=new ZipInputStream(Files.newInputStream(archive))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){
            String name=entry.getName();if(entry.isDirectory())continue;
            if(!allowed(name)||files.containsKey(name)||files.size()>100)throw new IOException("Invalid release layout: "+name);
            byte[] bytes=zip.readNBytes(128<<20);if(zip.read()!=-1)throw new IOException("Release entry exceeds limit");
            total+=bytes.length;if(total>(256L<<20))throw new IOException("Release is too large");files.put(name,bytes);
        }}
        if(!files.containsKey("release-manifest.json")||!files.containsKey("SHA256SUMS"))throw new IOException("Missing release metadata");
        Release release=Json.fromBytes(files.get("release-manifest.json"),Release.class);
        if(release.repository()!=2||release.protocol()!=2||!"alpha".equals(release.channel())||release.schema()>io.github.azotorp.wowuisync.server.db.ServerDb.SCHEMA_VERSION)throw new IOException("Incompatible release");
        Set<String> required=new HashSet<>(List.of("LICENSE","README.md","CHANGELOG.md","ENGINEERING-REPORT.md","client/wow-ui-sync.client.jar","server/wow-ui-sync.server.jar","client/README.txt","server/README.txt","server/wow-ui-sync.service","SHA256SUMS","release-manifest.json"));
        for(String script:SCRIPTS)for(String extension:List.of(".sh",".bat"))required.add("server/"+script+extension);
        if(!required.equals(files.keySet()))throw new IOException("Incomplete release layout");
        verifyJarBytes(files.get("client/wow-ui-sync.client.jar"),release.version(),"io.github.azotorp.wowuisync.client.ClientMain");
        verifyJarBytes(files.get("server/wow-ui-sync.server.jar"),release.version(),"io.github.azotorp.wowuisync.server.ServerMain");
        if(release.files()==null || release.files().size()!=files.size()-2)throw new IOException("Release file membership mismatch");
        for(var e:release.files().entrySet())if(!files.containsKey(e.getKey())||!Hashing.sha256Hex(files.get(e.getKey())).equals(e.getValue()))throw new IOException("Release checksum mismatch");
        Map<String,String> sums=new TreeMap<>();for(String line:new String(files.get("SHA256SUMS"),java.nio.charset.StandardCharsets.UTF_8).split("\n")){
            int split=line.indexOf("  ");if(split!=64)throw new IOException("Bad checksum list");String name=line.substring(split+2);if(sums.put(name,line.substring(0,64))!=null)throw new IOException("Duplicate checksum");
        }
        if(sums.size()!=files.size()-1)throw new IOException("Checksum membership mismatch");
        for(var e:files.entrySet())if(!e.getKey().equals("SHA256SUMS")&&!Hashing.sha256Hex(e.getValue()).equals(sums.get(e.getKey())))throw new IOException("Checksum list mismatch");
        return files;
    }
    public static void signature(Path archive,Path sig,Path key) throws Exception {
        String pem=Files.readString(key).replaceAll("-----[^-]+-----","").replaceAll("\\s","");
        PublicKey pub=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
        Signature verifier=Signature.getInstance("Ed25519");verifier.initVerify(pub);
        try(InputStream in=Files.newInputStream(archive)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)verifier.update(b,0,n);}
        if(!verifier.verify(Files.readAllBytes(sig)))throw new IOException("Release signature failed verification");
    }
    private static Map<String,String> options(String[] args) throws IOException {
        Map<String,String> map=new HashMap<>();for(int i=1;i<args.length;i++){
            String k=args[i];if(Set.of("--check","--dry-run","--allow-unsigned-local","--help").contains(k))map.put(k,"true");
            else if(Set.of("--data","--from","--sha256","--signature","--trust-key","--source","--checkpoint").contains(k)&&i+1<args.length)map.put(k,args[++i]);
            else throw new IOException("Unknown release option: "+k);
        }return map;
    }
    public static void command(String[] args) throws Exception {
        Map<String,String> opts=options(args);
        if(opts.containsKey("--help")){System.out.println("package --source <repo>\nupdate --data <dir> --from <zip> --signature <sig> --trust-key <public.pem> [--check|--dry-run]\nLocal unsigned build: add --allow-unsigned-local --sha256 <independently checked digest>\nrollback --data <dir> --checkpoint <migration-checkpoint>\nStop the service before update/rollback; start it after verification. No automatic fetching or scheduled updates.");return;}
        if(args[0].equals("package")){pack(Path.of(opts.getOrDefault("--source",".")));return;}
        Path data=ConfigIO.resolveDataDir(opts.get("--data"));if(data==null)throw new IOException("Pass --data");DataLayout layout=new DataLayout(data);
        Path home=Path.of(System.getProperty("wowuisync.home",ConfigIO.jarDir().toString())).toAbsolutePath().normalize();
        if(args[0].equals("rollback")){rollback(home,layout,opts.containsKey("--checkpoint")?Path.of(opts.get("--checkpoint")):null);return;}
        if(!opts.containsKey("--from"))throw new IOException("Pass a local release ZIP with --from; network self-updates are disabled");
        Path archive=Path.of(opts.get("--from")).toAbsolutePath();
        if(opts.containsKey("--signature")&&opts.containsKey("--trust-key"))signature(archive,Path.of(opts.get("--signature")),Path.of(opts.get("--trust-key")));
        else if(!opts.containsKey("--allow-unsigned-local")||!Hashing.sha256Hex(archive).equals(opts.get("--sha256")))throw new IOException("A trusted Ed25519 signature is required. Local unsigned builds require explicit opt-in and an independently checked SHA-256.");
        Map<String,byte[]> files=inspect(archive);Release release=Json.fromBytes(files.get("release-manifest.json"),Release.class);
        if(!release.version().matches("[0-9]+\\.[0-9]+\\.[0-9]+-alpha"))throw new IOException("Invalid release version");
        if(opts.containsKey("--check")||opts.containsKey("--dry-run")){System.out.println("Verified local release "+release.version()+"; data="+data+"; no changes made");return;}
        if(DataLock.running(data))throw new IOException("Stop the service before update. Active sessions and commits must drain first.");
        Path stage=home.resolve("releases").resolve(release.version()+"-"+Hashing.sha256Hex(archive).substring(0,12));
        try(var lock=DataLock.acquire(data)){
            if(Files.getFileStore(home).getUsableSpace()<Files.size(archive)*4+(256L<<20))throw new IOException("Insufficient update staging space");
            Files.createDirectories(stage);
            for(var e:files.entrySet()){Path target=stage.resolve(e.getKey());Files.createDirectories(target.getParent());ManifestIO.write(target,e.getValue());if(e.getKey().endsWith(".sh"))target.toFile().setExecutable(true,false);}
            Path checkpoint=RepositoryMaintenance.migrateLocked(layout);
            RepositoryMaintenance.verify(layout);
            String previous=Files.exists(home.resolve("active-release"))?Files.readString(home.resolve("active-release")).trim():".";
            String active=home.relativize(stage.resolve("server")).toString().replace('\\','/');
            ManifestIO.write(home.resolve("activation.json"),Json.toBytes(new Activation(previous,active,checkpoint.toString())));
            ManifestIO.write(home.resolve("active-release"),active.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.out.println("Activated "+release.version()+". Checkpoint: "+checkpoint+". Start the service and verify status.");
        }
    }
    private static void rollback(Path home,DataLayout layout,Path requested) throws Exception {
        try(var lock=DataLock.acquire(layout.root())) {
            Activation activation=Json.fromBytes(Files.readAllBytes(home.resolve("activation.json")),Activation.class);
            Path checkpoint=requested==null?Path.of(activation.checkpoint()):requested;
            @SuppressWarnings("unchecked") Map<String,String> hashes=Json.MAPPER.readValue(Files.readAllBytes(checkpoint.resolve("checksums.json")),Map.class);
            for(var e:hashes.entrySet()){
                Path source=checkpoint.resolve(e.getKey()).normalize();if(!source.startsWith(checkpoint.normalize())||!Hashing.sha256Hex(source).equals(e.getValue()))throw new IOException("Rollback checkpoint verification failed");
            }
            Path prior=home.resolve(activation.previous()).normalize();
            if(!prior.startsWith(home)||(!Files.isRegularFile(prior.resolve("wow-ui-sync.server.jar"))&&!Files.isRegularFile(prior.resolve("wow-ui-sync-server.jar"))))throw new IOException("Previous binary is unavailable; data was not changed");
            // Preserve all post-upgrade metadata before restoring the matching checkpoint.
            Path rescued=layout.root().resolve("rollback-preserved-"+System.currentTimeMillis());Files.createDirectories(rescued);
            for(String name:List.of("wowuisync.db","wowuisync.db-wal","wowuisync.db-shm","server.json")){
                Path file=layout.root().resolve(name);if(Files.exists(file))Files.move(file,rescued.resolve(name));
            }
            if(Files.exists(layout.accounts()))try(var paths=Files.walk(layout.accounts())) {
                List<Path> metadata=paths.filter(x->x.getFileName().toString().equals("head-v2")||x.getFileName().toString().equals("snapshots-v2")).toList();
                for(Path file:metadata) {
                    if(!Files.exists(file))continue;
                    Path dest=rescued.resolve(layout.root().relativize(file));Files.createDirectories(dest.getParent());Files.move(file,dest);
                }
            }
            Files.deleteIfExists(layout.root().resolve("repository-v2.json"));
            for(var e:hashes.entrySet()) {
                Path target;
                if(e.getKey().startsWith("profiles/")) {
                    Path relative=Path.of(e.getKey());
                    if(relative.getNameCount()<4)throw new IOException("Invalid profile metadata checkpoint");
                    target=layout.profileDir(relative.getName(1).toString(),relative.getName(2).toString()).resolve(relative.subpath(3,relative.getNameCount()));
                } else target=layout.root().resolve(e.getKey());
                if(!target.toAbsolutePath().normalize().startsWith(layout.root().toAbsolutePath().normalize()))throw new IOException("Invalid checkpoint path");
                Files.createDirectories(target.getParent());Files.copy(checkpoint.resolve(e.getKey()),target,StandardCopyOption.REPLACE_EXISTING);
            }
            ManifestIO.write(home.resolve("active-release"),activation.previous().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.out.println("Checkpoint restored offline. Post-upgrade metadata preserved in "+rescued+". Start the matching previous release only.");
        }
    }
    private ReleaseManager(){}
}
