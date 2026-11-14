package io.github.azotorp.wowuisync.client.sync;
import io.github.azotorp.wowuisync.client.data.ClientDb;
import io.github.azotorp.wowuisync.client.util.CancelledException;
import io.github.azotorp.wowuisync.common.path.Exclusions;
import io.github.azotorp.wowuisync.common.hash.Hashing;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ScannerCacheTest {
 @TempDir Path dir;
 Path wow,dbfile; Scanner.Statistics stats;
 final Exclusions ex=new Exclusions(List.of());
 final Scanner.Progress progress=new Scanner.Progress(){public void update(int d,int t){}public void statistics(Scanner.Statistics s){stats=s;}};
 @BeforeEach void fixture()throws Exception {wow=dir.resolve("wow");dbfile=dir.resolve("client.db");write("WTF/a.lua","one");write("Interface/AddOns/A/a.lua","two");}
 Path write(String name,String text)throws Exception {Path p=wow.resolve(name);Files.createDirectories(p.getParent());Files.writeString(p,text);Files.setLastModifiedTime(p,FileTime.fromMillis(System.currentTimeMillis()-10000));return p;}
 ClientDb db()throws Exception{return ClientDb.open(dbfile,dir.resolve("backups"));}
 LocalScan scan(ClientDb db)throws Exception{return Scanner.scan(wow,ex,db,progress,()->false);}
 @Test void persistsAcrossRestartAndDoesNotReadUnchangedContentOrRequireStaging()throws Exception {
  String fp;try(var db=db()){fp=scan(db).manifest().fingerprint();assertEquals(2,stats.hashedFiles());}
  try(var db=db()){try(var s=Files.list(db.staging())){for(Path p:s.toList())Files.delete(p);}assertEquals(fp,scan(db).manifest().fingerprint());assertEquals(2,stats.cacheHits());assertEquals(0,stats.bytesHashed());assertEquals(0,stats.hashedFiles());}
 }
 @Test void changesAdditionsDeletionsAndRenames()throws Exception {
  try(var db=db()){scan(db);write("WTF/a.lua","ONE");var s=scan(db);assertEquals(1,stats.hashedFiles());assertEquals(Hashing.sha256Hex("ONE".getBytes()),s.manifest().get("WTF/a.lua").sha256());
   Files.delete(wow.resolve("WTF/a.lua"));write("WTF/new.lua","new");s=scan(db);assertNull(s.manifest().get("WTF/a.lua"));assertEquals(1,stats.hashedFiles());
   Files.move(wow.resolve("WTF/new.lua"),wow.resolve("WTF/renamed.lua"));assertNotNull(scan(db).manifest().get("WTF/renamed.lua"));assertEquals(1,stats.hashedFiles());}
 }
 @Test void restoredMtimeCannotHideSameSizeChangeOnUnixAndStrictScanAlwaysVerifies()throws Exception {
  try(var db=db()){scan(db);Path p=wow.resolve("WTF/a.lua");var time=Files.getLastModifiedTime(p);Files.writeString(p,"ONE");Files.setLastModifiedTime(p,time);
   if(Files.getFileStore(p).supportsFileAttributeView("unix")){assertEquals(Hashing.sha256Hex("ONE".getBytes()),scan(db).manifest().get("WTF/a.lua").sha256());assertEquals(1,stats.hashedFiles());}
   var strict=Scanner.verified(wow,ex,db,progress,()->false);assertEquals(2,stats.hashedFiles());assertEquals(Hashing.sha256Hex("ONE".getBytes()),strict.manifest().get("WTF/a.lua").sha256());}
 }
 @Test void recentFilesAreNeverTrusted()throws Exception {try(var db=db()){Path p=wow.resolve("WTF/a.lua");Files.setLastModifiedTime(p,FileTime.fromMillis(System.currentTimeMillis()));scan(db);scan(db);assertEquals(1,stats.hashedFiles());}}
 @Test void malformedCacheRehashesWithoutResettingConnectionsOrPins()throws Exception {
  try(var db=db()){db.pin("host:1","pin");scan(db);}
  try(var c=DriverManager.getConnection("jdbc:sqlite:"+dbfile);var s=c.createStatement()){s.execute("UPDATE scan_cache_v2 SET payload='broken'");}
  try(var db=db()){assertEquals("pin",db.pinned("host:1"));scan(db);assertEquals(2,stats.hashedFiles());scan(db);assertEquals(2,stats.cacheHits());}
 }
 @Test void missingAndCorruptStagingRebuiltOnlyWhenRequestedAndChangedSourcesRefused()throws Exception {
  try(var db=db()){var s=scan(db);var e=s.manifest().get("WTF/a.lua");Files.writeString(db.staging().resolve(e.sha256()),"bad");
   Scanner.ensureStaged(s,Set.of(e.sha256()),db.staging(),()->false);assertEquals(e.sha256(),Hashing.sha256Hex(db.staging().resolve(e.sha256())));
   Files.delete(db.staging().resolve(e.sha256()));write("WTF/a.lua","new");assertThrows(Exception.class,()->Scanner.ensureStaged(s,Set.of(e.sha256()),db.staging(),()->false));}
 }
 @Test void cancellationDoesNotPublishPartialCache()throws Exception {
  try(var db=db()){assertThrows(CancelledException.class,()->Scanner.scan(wow,ex,db,new Scanner.Progress(){public void update(int d,int t){}},()->true));assertTrue(db.scanCache(wow.toAbsolutePath().toString()).isEmpty());}
 }
 @Test void symlinksUnsafeNamesAndCaseCollisionsFailClosed()throws Exception {
  try(var db=db()){
   Path link=wow.resolve("WTF/link");try{Files.createSymbolicLink(link,dir);}catch(UnsupportedOperationException|java.io.IOException e){Assumptions.abort("Symlink creation unavailable");}
   assertThrows(Exception.class,()->scan(db));Files.delete(link);
   write("WTF/CON.lua","x");assertThrows(Exception.class,()->scan(db));Files.delete(wow.resolve("WTF/CON.lua"));
   Path upper=write("WTF/A.lua","x");
   // macOS commonly uses a case-insensitive volume too: two spellings may be one file.
   if(Files.isSameFile(upper,wow.resolve("WTF/a.lua"))) assertNotNull(scan(db));
   else assertThrows(Exception.class,()->scan(db));
  }
 }
 @Test void schemaOneMigrationPreservesPinsAndProducesConsistentBackup()throws Exception {
  try(var db=db()){db.pin("host:1","pin");}
  try(var c=DriverManager.getConnection("jdbc:sqlite:"+dbfile);var s=c.createStatement()){s.execute("DROP TABLE scan_cache_v2");s.execute("UPDATE schema_version SET version=1");}
  try(var db=db()){assertEquals("pin",db.pinned("host:1"));scan(db);}
  try(var paths=Files.list(dir.resolve("backups"))){assertEquals(1,paths.count());}
 }
 @Test void windowsNullFileKeyUsesCreationHintWhileUnknownProvidersRemainUntrusted()throws Exception {
  var real=Files.readAttributes(wow.resolve("WTF/a.lua"),java.nio.file.attribute.BasicFileAttributes.class);
  var missingKey=new java.nio.file.attribute.BasicFileAttributes(){
   public FileTime lastModifiedTime(){return real.lastModifiedTime();}public FileTime lastAccessTime(){return real.lastAccessTime();}public FileTime creationTime(){return real.creationTime();}
   public boolean isRegularFile(){return true;}public boolean isDirectory(){return false;}public boolean isSymbolicLink(){return false;}public boolean isOther(){return false;}public long size(){return real.size();}public Object fileKey(){return null;}
  };
  assertNotNull(ScanCache.identity(missingKey,true));assertNull(ScanCache.identity(missingKey,false));
 }

 @Test void scansAndCachesThroughAncestorAliasWithoutFollowingInternalLinks()throws Exception {
  Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"));
  Path alias=dir.resolve("alias");Files.createSymbolicLink(alias,dir);
  try(var db=db()) {
   String expected=scan(db).manifest().fingerprint();
   wow=alias.resolve("wow");
   assertEquals(expected,scan(db).manifest().fingerprint());
   assertEquals(expected,scan(db).manifest().fingerprint());assertEquals(2,stats.cacheHits());
   Path outside=Files.createDirectory(dir.resolve("outside"));
   Files.createSymbolicLink(wow.resolve("WTF/link"),outside);
   assertThrows(java.io.IOException.class,()->scan(db));
  }
 }

}
