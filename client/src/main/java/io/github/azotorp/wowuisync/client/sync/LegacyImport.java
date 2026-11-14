package io.github.azotorp.wowuisync.client.sync;
import io.github.azotorp.wowuisync.client.ClientApp;
import io.github.azotorp.wowuisync.client.data.BackupRecord;
import io.github.azotorp.wowuisync.client.wow.Flavor;
import io.github.azotorp.wowuisync.common.chunk.*;
import io.github.azotorp.wowuisync.common.hash.Hashing;
import io.github.azotorp.wowuisync.common.manifest.*;
import io.github.azotorp.wowuisync.common.path.SyncPaths;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
/** Reads legacy backup bytes; never changes or copies the original directories. */
public final class LegacyImport {
 public static LocalScan scan(ClientApp app,Flavor flavor,BackupRecord backup)throws Exception{
  List<ManifestEntry> entries=new ArrayList<>();Map<String,Path> files=new HashMap<>();Path chunks=app.paths().stagedObjects();Files.createDirectories(chunks);
  if(!backup.suffix().matches("[A-Za-z0-9_-]+"))throw new IOException("Invalid legacy backup name");
  for(String tree:List.of("Interface","WTF")){
   Path base=flavor.path().resolve(tree+"_"+backup.suffix());if(!Files.exists(base))continue;
   if(Files.isSymbolicLink(base))throw new IOException("Linked legacy directory");
   Files.walkFileTree(base,new SimpleFileVisitor<>(){
    @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException{
     String relative=tree+"/"+base.relativize(file).toString().replace('\\','/');
     if(!SyncPaths.isSyncedPath(relative)||app.config().exclusions(flavor.profileId()).isExcluded(relative))return FileVisitResult.CONTINUE;
     if(!attrs.isRegularFile()||attrs.isSymbolicLink())throw new IOException("Linked legacy file");SyncPaths.resolve(flavor.path(),relative);
     var hash=Hashing.newSha256();List<ChunkRef> refs;
     try(InputStream in=new java.security.DigestInputStream(Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS),hash)){
      refs=ContentChunker.split(in,(ref,bytes)->{Path target=chunks.resolve(ref.hash());if(!Files.exists(target)||!Hashing.sha256Hex(target).equals(ref.hash()))TransferEngine.writeStaged(chunks,ref.hash(),bytes);});
     }
     var after=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
     if(attrs.size()!=after.size()||!attrs.lastModifiedTime().equals(after.lastModifiedTime()))throw new IOException("Legacy file changed during import");
     entries.add(new ManifestEntry(relative,attrs.size(),Hashing.hex(hash.digest()),refs));files.put(SyncPaths.key(relative),file);return FileVisitResult.CONTINUE;
    }
    @Override public FileVisitResult visitFileFailed(Path p,IOException error)throws IOException{throw error;}
   });
  }
  if(entries.isEmpty())throw new IOException("No eligible legacy files found");return new LocalScan(flavor.path(),new Manifest(entries),files,List.of(),List.of());
 }
 private LegacyImport(){}
}
