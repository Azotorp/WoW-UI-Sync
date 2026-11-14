package io.github.azotorp.wowuisync.server;
import io.github.azotorp.wowuisync.common.*;
import io.github.azotorp.wowuisync.common.codec.Codec;
import io.github.azotorp.wowuisync.common.crypto.Passwords;
import io.github.azotorp.wowuisync.common.hash.Hashing;
import io.github.azotorp.wowuisync.common.json.Json;
import io.github.azotorp.wowuisync.common.manifest.*;
import io.github.azotorp.wowuisync.common.object.ObjectFiles;
import io.github.azotorp.wowuisync.server.config.*;
import io.github.azotorp.wowuisync.server.db.*;
import io.github.azotorp.wowuisync.server.maintenance.*;
import io.github.azotorp.wowuisync.server.security.TlsKeys;
import io.github.azotorp.wowuisync.server.sync.Storage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class MaintenanceTest {
 @TempDir Path dir;
 private String account;
 private DataLayout legacy()throws Exception{
  DataLayout l=new DataLayout(dir.resolve("data"));Files.createDirectories(l.root());var cfg=new ServerConfig();cfg.dataDir=l.root().toString();ConfigIO.write(l.config(),cfg);TlsKeys.generate(l.keystore(),"localhost");
  account=UUID.randomUUID().toString();var password=Passwords.hash("password1".toCharArray());
  try(var db=ServerDb.open(l.db(),l.dbBackups())){db.insertAccount(new AccountRow(account,"root",Role.ROOT,null,null,AccountRow.AUTH_PASSWORD,password.hashB64(),password.saltB64(),password.iterations(),null,123L,null,null,null,0,null,0));}
  try(var c=DriverManager.getConnection("jdbc:sqlite:"+l.db());var stmt=c.createStatement()){stmt.execute("DROP TABLE request_cooldowns");stmt.execute("DROP TABLE verified_emails");stmt.execute("UPDATE schema_version SET version=1");}
  byte[] data="legacy settings".getBytes();String hash=Hashing.sha256Hex(data);ObjectFiles.write(ObjectFiles.shardPath(l.objects(account,"forever"),hash),data,Codec.DEFLATE,3);
  var entries=List.of(new ManifestEntry("WTF/legacy.lua",data.length,hash));var m=new Manifest(entries);Files.createDirectories(l.currentManifest(account,"forever").getParent());
  ManifestIO.write(l.currentManifest(account,"forever"),Codec.preferred().encodeTagged(Json.toBytes(new Storage.StoredManifest(m.fingerprint(),entries,123L,"old pc","root"))));return l;
 }
 @Test void migrationPreservesIdentityDataAndResumesIdempotently()throws Exception{
  DataLayout l=legacy();String fp=TlsKeys.fingerprint(TlsKeys.load(l.keystore()));byte[] legacyManifest=Files.readAllBytes(l.currentManifest(account,"forever"));
  assertTrue(RepositoryMaintenance.needsMigration(l));Path checkpoint=RepositoryMaintenance.migrate(l);assertTrue(Files.exists(checkpoint.resolve("checksums.json")));
  assertFalse(RepositoryMaintenance.needsMigration(l));assertEquals(fp,TlsKeys.fingerprint(TlsKeys.load(l.keystore())));
  try(var db=ServerDb.open(l.db(),l.dbBackups())){assertEquals(account,db.root().orElseThrow().id());assertTrue(Passwords.verify("password1".toCharArray(),db.root().orElseThrow().passwordHash(),db.root().orElseThrow().passwordSalt(),db.root().orElseThrow().passwordIterations()));}
  Storage s=new Storage(l);assertEquals(1,s.snapshots(account,"forever").size());assertEquals("migration_baseline",s.snapshots(account,"forever").getFirst().sourceType());assertArrayEquals(legacyManifest,Files.readAllBytes(l.currentManifest(account,"forever")));
  RepositoryMaintenance.verify(l);RepositoryMaintenance.migrate(l);assertEquals(1,s.snapshots(account,"forever").size());
 }
 @Test void corruptLegacyContentCannotActivateSnapshot()throws Exception{
  DataLayout l=legacy();Storage s=new Storage(l);var m=s.readManifest(account,"forever");Path object=s.objectPath(account,"forever",m.entries().getFirst().sha256());Files.write(object,new byte[]{1,2,3});
  assertThrows(Exception.class,()->RepositoryMaintenance.migrate(l));assertNull(s.headId(account,"forever"));assertTrue(Files.exists(l.currentManifest(account,"forever")));
 }
 @Test void maintenanceCannotRaceRunningProcess()throws Exception{
  DataLayout l=legacy();try(var lock=DataLock.acquire(l.root())){assertThrows(Exception.class,()->RepositoryMaintenance.migrate(l));assertTrue(DataLock.running(l.root()));}
 }
 @Test void gcProtectsAllRetainedSnapshotsAndRejectsActiveLeases()throws Exception{
  DataLayout l=new DataLayout(dir.resolve("gc"));Files.createDirectories(l.root());Storage s=new Storage(l);String a=UUID.randomUUID().toString();
  byte[] one="one".getBytes(),two="two".getBytes();String h1=Hashing.sha256Hex(one),h2=Hashing.sha256Hex(two);s.storeObject(a,"forever",h1,one,false);s.storeObject(a,"forever",h2,two,false);
  var m1=new Manifest(List.of(new ManifestEntry("WTF/a",3,h1)));var m2=new Manifest(List.of(new ManifestEntry("WTF/a",3,h2)));
  var first=s.commitSnapshot(a,"forever",new Storage.StoredManifest(m1.fingerprint(),m1.entries(),1L,"pc","root"),"push",null,true);
  s.commitSnapshot(a,"forever",new Storage.StoredManifest(m2.fingerprint(),m2.entries(),2L,"pc","root"),"push",null,true);
  assertEquals(0,s.collect(a,0));assertTrue(s.hasObject(a,"forever",h1));
  s.beginTransfer(a,"forever");assertThrows(Exception.class,()->s.collect(a,0));s.endTransfer(a,"forever");
  s.annotate(a,"forever",first.id(),"keep",true,false);assertThrows(Exception.class,()->s.annotate(a,"forever",first.id(),null,null,true));
  s.annotate(a,"forever",first.id(),null,false,false);s.annotate(a,"forever",first.id(),null,null,true);
  assertThrows(Exception.class,()->s.snapshot(a,"forever",first.id()));Thread.sleep(5);assertTrue(s.collect(a,0)>0);assertFalse(s.hasObject(a,"forever",h1));assertTrue(s.hasObject(a,"forever",h2));
 }
 @Test void startupAutomaticallyMigratesUnderExistingLock()throws Exception{
  DataLayout l=legacy();byte[] identity=Files.readAllBytes(l.keystore());
  try(var lock=DataLock.acquire(l.root())){RepositoryMaintenance.requireSupported(l);}
  assertFalse(RepositoryMaintenance.needsMigration(l));assertArrayEquals(identity,Files.readAllBytes(l.keystore()));
  Storage s=new Storage(l);s.warmStatusMetadata();assertNotNull(s.statusManifest(account,"forever"));assertEquals(s.logicalBytes(account),s.statusUsage(account));
  try(var paths=Files.list(l.root().resolve("migration-checkpoints"))){assertEquals(1,paths.count());}
  try(var lock=DataLock.acquire(l.root())){RepositoryMaintenance.requireSupported(l);}
  try(var paths=Files.list(l.root().resolve("migration-checkpoints"))){assertEquals(1,paths.count());}
 }
 @Test void newerAndUnrecognizedSchemasAreRefusedBeforeMigration()throws Exception{
  DataLayout l=legacy();
  try(var c=DriverManager.getConnection("jdbc:sqlite:"+l.db());var stmt=c.createStatement()){stmt.execute("UPDATE schema_version SET version=99");}
  try(var lock=DataLock.acquire(l.root())){assertThrows(Exception.class,()->RepositoryMaintenance.requireSupported(l));}
  assertFalse(Files.exists(l.root().resolve("migration-checkpoints")));
  try(var c=DriverManager.getConnection("jdbc:sqlite:"+l.db());var stmt=c.createStatement()){stmt.execute("UPDATE schema_version SET version=1");stmt.execute("DROP TABLE invites");}
  assertThrows(Exception.class,()->RepositoryMaintenance.migrate(l));assertFalse(Files.exists(l.root().resolve("migration-checkpoints")));
 }
 @Test void failedSchemaMigrationRollsBackAndCheckpointIncludesCommittedWal()throws Exception{
  DataLayout l=legacy();
  try(var c=DriverManager.getConnection("jdbc:sqlite:"+l.db());var stmt=c.createStatement()){
   stmt.execute("PRAGMA journal_mode=WAL");stmt.execute("PRAGMA wal_autocheckpoint=0");
   stmt.execute("UPDATE accounts SET email='preserved@example.test'");
   // Recognized v1 plus a conflicting new table must fail transactionally, not reset.
   stmt.execute("CREATE TABLE verified_emails (conflict TEXT)");
   assertThrows(Exception.class,()->RepositoryMaintenance.migrate(l));
   try(var r=stmt.executeQuery("SELECT version FROM schema_version")){assertTrue(r.next());assertEquals(1,r.getInt(1));}
   try(var r=stmt.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='request_cooldowns'")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
  }
  Path checkpoint;try(var paths=Files.list(l.root().resolve("migration-checkpoints"))){checkpoint=paths.findFirst().orElseThrow();}
  try(var c=DriverManager.getConnection("jdbc:sqlite:"+checkpoint.resolve("wowuisync.db"));var st=c.createStatement();var r=st.executeQuery("SELECT email FROM accounts")){assertTrue(r.next());assertEquals("preserved@example.test",r.getString(1));}
 }
 @Test void statusReadsCurrentRevisionWithoutWalkingSnapshotHistoryOrObjects()throws Exception{
  DataLayout l=new DataLayout(dir.resolve("status"));Files.createDirectories(l.root());Storage s=new Storage(l);String a=UUID.randomUUID().toString();
  byte[] data="one".getBytes();String h=Hashing.sha256Hex(data);s.storeObject(a,"forever",h,data,false);
  var m=new Manifest(List.of(new ManifestEntry("WTF/a",3,h)));
  var first=s.commitSnapshot(a,"forever",new Storage.StoredManifest(m.fingerprint(),m.entries(),1L,"pc","root"),"push",null,true);
  s.commitSnapshot(a,"forever",new Storage.StoredManifest(m.fingerprint(),m.entries(),2L,"pc","root"),"push",null,true);
  s.warmStatusMetadata();Files.writeString(l.snapshots(a,"forever").resolve(first.id()+".json"),"corrupt history");
  Files.delete(s.objectPath(a,"forever",h));
  for(int i=0;i<10;i++){assertEquals(m.fingerprint(),s.statusManifest(a,"forever").fingerprint());assertEquals(3,s.statusUsage(a));}
  assertThrows(Exception.class,()->s.snapshot(a,"forever",first.id()));assertThrows(Exception.class,()->s.verify(a,"forever",m));
 }

}
