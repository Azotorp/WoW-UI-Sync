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
  try(var db=db()){assertFalse(Files.exists(db.staging()));assertEquals(fp,scan(db).manifest().fingerprint());assertEquals(2,stats.cacheHits());assertEquals(0,stats.bytesHashed());assertEquals(0,stats.hashedFiles());}
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
  try(var db=db()){var s=scan(db);var e=s.manifest().get("WTF/a.lua");Files.createDirectories(db.staging());Files.writeString(db.staging().resolve(e.sha256()),"bad");
   Scanner.ensureStaged(s,Set.of(e.sha256()),db.staging(),()->false);assertEquals(e.sha256(),Hashing.sha256Hex(db.staging().resolve(e.sha256())));
   Files.delete(db.staging().resolve(e.sha256()));write("WTF/a.lua","new");assertThrows(Exception.class,()->Scanner.ensureStaged(s,Set.of(e.sha256()),db.staging(),()->false));}
 }
 @Test void cancellationDoesNotPublishPartialCache()throws Exception {
  try(var db=db()){assertThrows(CancelledException.class,()->Scanner.scan(wow,ex,db,new Scanner.Progress(){public void update(int d,int t){}},()->true));assertTrue(db.scanCache(wow.toAbsolutePath().toString()).isEmpty());
   var finalPhase=new java.util.concurrent.atomic.AtomicBoolean();var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
   assertThrows(CancelledException.class,()->Scanner.scan(wow,ex,db,new Scanner.Progress(){
    public void update(int d,int t){}
    public void phase(String phase){if(phase.contains("completing local manifest"))finalPhase.set(true);}
    public void statistics(Scanner.Statistics stats){if(finalPhase.get())cancelled.set(true);}
   },cancelled::get));
   assertEquals(2,db.scanCache(wow.toAbsolutePath().toString()).size()); // hints survive, no LocalScan is published
  }
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

 @Test void partialVerifiedHintsSurviveCancellationWithoutDeletingUnseenRowsOrChangingBaseline()throws Exception {
  for(int i=0;i<600;i++)write("WTF/many/"+i+".lua","file "+i);
  try(var db=db()) {
   scan(db);var old=db.scanCache(wow.toAbsolutePath().toString());
   Files.delete(wow.resolve("WTF/a.lua"));
   try(var c=DriverManager.getConnection("jdbc:sqlite:"+dbfile);var sql=c.createStatement()){sql.execute("DELETE FROM scan_cache_v2 WHERE path<>'WTF/a.lua'");}
   db.setBase("host","account","flavor","baseline");
   var content=new java.util.concurrent.atomic.AtomicBoolean();var checks=new java.util.concurrent.atomic.AtomicInteger();
   assertThrows(CancelledException.class,()->Scanner.scan(wow,ex,db,new Scanner.Progress(){
    public void update(int d,int t){}
    public void phase(String phase){if(phase.contains("workers"))content.set(true);}
   },()->content.get()&&checks.incrementAndGet()>2300));
   var partial=db.scanCache(wow.toAbsolutePath().toString());
   assertTrue(partial.size()>1);assertTrue(partial.size()<old.size());
   assertNotNull(partial.get("WTF/a.lua")); // incomplete enumeration cannot authorize deletion
   assertEquals("baseline",db.base("host","account","flavor"));
   assertFalse(Files.exists(db.staging()));
  }
  try(var db=db()){scan(db);assertTrue(stats.cacheHits()>0);assertNull(db.scanCache(wow.toAbsolutePath().toString()).get("WTF/a.lua"));}
 }
 @Test void discoveryMetadataCanAgeWhileAnExternalUpdaterWritesBeforeContentRead()throws Exception {
  try(var db=db()) {
   var changed=new java.util.concurrent.atomic.AtomicBoolean();
   var result=Scanner.scan(wow,ex,db,new Scanner.Progress(){
    public void update(int d,int t){}
    public void phase(String phase){if(phase.contains("workers")&&changed.compareAndSet(false,true))try{write("WTF/a.lua","TSM updated after discovery");}catch(Exception e){throw new RuntimeException(e);}}
   },()->false);
   assertEquals(Hashing.sha256Hex("TSM updated after discovery".getBytes()),result.manifest().get("WTF/a.lua").sha256());
  }
 }
 @Test void mutationDuringContentReadFailsAndNeverCachesUnverifiedBytes()throws Exception {
  Path p=write("WTF/mutating.lua","x".repeat(2<<20));
  var calls=new java.util.concurrent.atomic.AtomicInteger();
  assertThrows(java.io.IOException.class,()->Scanner.hashFile(wow,p,null,()->{
   if(calls.incrementAndGet()==3)try{Files.writeString(p,"changed");}catch(Exception e){throw new RuntimeException(e);}return false;
  }));
 }
 @Test void parallelAndSequentialManifestsAndChunkReferencesMatchAndCancelledWorkersTerminate()throws Exception {
  byte[] big=new byte[20<<20];new Random(17).nextBytes(big);Path p=wow.resolve("WTF/big.lua");Files.write(p,big);
  try(var db=db()) {
   try {
    System.setProperty("wowuisync.scanWorkers","1");var one=Scanner.verified(wow,ex,db,progress,()->false);
    for(String workers:List.of("2","4")) {
     System.setProperty("wowuisync.scanWorkers",workers);var parallel=Scanner.verified(wow,ex,db,progress,()->false);
     assertEquals(one.manifest().entries(),parallel.manifest().entries());assertEquals(one.manifest().fingerprint(),parallel.manifest().fingerprint());
     var active=new java.util.concurrent.atomic.AtomicBoolean();var calls=new java.util.concurrent.atomic.AtomicInteger();
     assertThrows(CancelledException.class,()->Scanner.verified(wow,ex,db,new Scanner.Progress(){public void update(int d,int t){}public void phase(String phase){if(phase.contains("workers"))active.set(true);}},()->active.get()&&calls.incrementAndGet()>8));
     assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().equals("wus-scan")));
    }
    assertFalse(Files.exists(db.staging()));
   } finally {System.clearProperty("wowuisync.scanWorkers");}
  }
 }
 @Test void missesExplainStrictCorruptRecentAndAbsentHints()throws Exception {
  try(var db=db()) {
   scan(db);assertEquals(2,stats.misses().get("absent entry"));
   Scanner.verified(wow,ex,db,progress,()->false);assertEquals(2,stats.misses().get("strict verification"));
   try(var c=DriverManager.getConnection("jdbc:sqlite:"+dbfile);var sql=c.createStatement()){sql.execute("UPDATE scan_cache_v2 SET checksum='bad'");}
   scan(db);assertEquals(2,stats.misses().get("corrupt entry"));
   Files.setLastModifiedTime(wow.resolve("WTF/a.lua"),FileTime.fromMillis(System.currentTimeMillis()));scan(db);scan(db);assertEquals(1,stats.misses().get("recent timestamp"));
  }
 }

 @Test void normalCloseFlushesLastBatchInASeparateProcessAndRestartReusesIt()throws Exception {
  for(int i=0;i<500;i++)write("WTF/close/"+i+".lua","close "+i);
  String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
  var process=new ProcessBuilder(executable,"-cp",System.getProperty("java.class.path"),CloseProbe.class.getName(),wow.toString(),dbfile.toString(),dir.resolve("backups").toString()).redirectErrorStream(true).redirectOutput(dir.resolve("close.log").toFile()).start();
  assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue(),Files.readString(dir.resolve("close.log")));
  try(var db=db()){assertFalse(db.scanCache(wow.toAbsolutePath().toString()).isEmpty());scan(db);assertTrue(stats.cacheHits()>0);}
 }
 public static class CloseProbe {
  public static void main(String[] args)throws Exception {
   Path wow=Path.of(args[0]);var started=new java.util.concurrent.CountDownLatch(1);
   var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
   try(var db=ClientDb.open(Path.of(args[1]),Path.of(args[2]))) {
    Thread scan=new Thread(()->{try{Scanner.scan(wow,new Exclusions(List.of()),db,new Scanner.Progress(){public void update(int d,int t){if(d>0&&t>0)started.countDown();}},()->false);}catch(CancelledException expected){}catch(Throwable e){failure.set(e);}});
    scan.start();if(!started.await(20,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Scan never started");
    Scanner.cancelAndAwait();scan.join();if(failure.get()!=null)throw new AssertionError(failure.get());
   }
  }
 }

}
