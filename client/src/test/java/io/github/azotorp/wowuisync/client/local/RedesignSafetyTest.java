package io.github.azotorp.wowuisync.client.local;
import io.github.azotorp.wowuisync.client.data.*;
import io.github.azotorp.wowuisync.client.sync.Scanner;
import io.github.azotorp.wowuisync.client.util.CancelledException;
import io.github.azotorp.wowuisync.client.wow.FlavorDetector;
import io.github.azotorp.wowuisync.common.path.Exclusions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RedesignSafetyTest {
    @TempDir Path dir;
    Path flavor() throws Exception {Path f=dir.resolve("wow/_forever_");Files.createDirectories(f);return f;}
    ClientDb db() throws Exception{return ClientDb.open(dir.resolve("client/client.db"),dir.resolve("client/backups"));}
    void write(Path p,String s)throws Exception{Files.createDirectories(p.getParent());Files.writeString(p,s);}
    @Test void freshInstallAcceptedWithoutInterfaceOrWtf() throws Exception {
        Path f=flavor();Files.writeString(f.resolve(".flavor.info"),"marker");
        assertTrue(FlavorDetector.detect(f.getParent()).getFirst().valid());
    }
    @Test void canceledScanPersistsCompletedWorkAndRestartUsesIt() throws Exception {
        Path f=flavor();for(int i=0;i<180;i++)write(f.resolve("Interface/AddOns/Example/"+i+".lua"),"-- synthetic "+i);
        AtomicInteger done=new AtomicInteger();
        try(ClientDb db=db()) {
            assertThrows(CancelledException.class,()->Scanner.scan(f,new Exclusions(List.of()),db,(d,t)->done.set(d),()->done.get()>=64));
            assertFalse(db.scanCache(f.toRealPath().toString()).isEmpty());
        }
        try(ClientDb db=db()) {
            var scan=Scanner.scan(f,new Exclusions(List.of()),db,null,()->false);
            assertEquals(180,scan.manifest().size());
            var cache=db.scanCache(f.toRealPath().toString());assertEquals(180,cache.size());
            Path changed=f.resolve("Interface/AddOns/Example/3.lua");String before=scan.manifest().get("Interface/AddOns/Example/3.lua").sha256();
            var timestamp=Files.getLastModifiedTime(changed);write(changed,"-- changed xx");Files.setLastModifiedTime(changed,timestamp);
            var after=Scanner.scan(f,new Exclusions(List.of()),db,null,()->false);
            assertNotEquals(before,after.manifest().get("Interface/AddOns/Example/3.lua").sha256());
            db.checkpointScanCache(f.toRealPath().toString(),List.of(new ClientDb.ScanCache("Interface/AddOns/Example/3.lua",1,"invalid","invalid","corrupt",0)));
            assertEquals(after.manifest().fingerprint(),Scanner.scan(f,new Exclusions(List.of()),db,null,()->false).manifest().fingerprint());
        }
    }
    public static class CrashScan {
        public static void main(String[] args) throws Exception {
            Path root=Path.of(args[0]),file=Path.of(args[1]);
            try(ClientDb db=ClientDb.open(file,file.getParent().resolve("backups"))) {
                Scanner.scan(root,new Exclusions(List.of()),db,(done,total)->{if(done>=128)Runtime.getRuntime().halt(17);},()->false);
            }
        }
    }
    @Test void processKilledDuringScanRecoversCheckpointedCache() throws Exception {
        Path f=flavor();for(int i=0;i<500;i++)write(f.resolve("Interface/AddOns/CrashFixture/"+i+".lua"),"-- crash probe "+i);
        Path dbFile=dir.resolve("client/client.db");
        String executable=Path.of(System.getProperty("java.home"),"bin","java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        Process child=new ProcessBuilder(executable,"-cp",classpath,CrashScan.class.getName(),f.toString(),dbFile.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(child.waitFor(20,java.util.concurrent.TimeUnit.SECONDS));assertEquals(17,child.exitValue());
        try(ClientDb db=db()){assertTrue(db.scanCache(f.toRealPath().toString()).size()>=64);assertEquals(500,Scanner.scan(f,new Exclusions(List.of()),db,null,()->false).manifest().size());}
    }
    @Test void backupCategoryMatrixAndRestorePreserveUnselectedCategories() throws Exception {
        Path f=flavor();write(f.resolve("Interface/file"),"old interface");write(f.resolve("WTF/file"),"old wtf");
        try(ClientDb db=db()) {
            for(boolean bi:List.of(false,true))for(boolean bw:List.of(false,true)) {
                BackupRecord b=Backups.create(db,f,"test",null,null,bi,bw,()->false);
                if(!bi&&!bw){assertNull(b);continue;}
                Path snapshot=f.resolve(".wus-backups/"+b.suffix());
                assertEquals(bi,Files.exists(snapshot.resolve("Interface/file")));assertEquals(bw,Files.exists(snapshot.resolve("WTF/file")));
                write(f.resolve("Interface/file"),"changed interface");write(f.resolve("WTF/file"),"changed wtf");
                Backups.restore(db,f,"forever",b,null);
                assertEquals(bi?"old interface":"changed interface",Files.readString(f.resolve("Interface/file")));
                assertEquals(bw?"old wtf":"changed wtf",Files.readString(f.resolve("WTF/file")));
                write(f.resolve("Interface/file"),"old interface");write(f.resolve("WTF/file"),"old wtf");
            }
        }
    }
    @Test void canceledBackupNeverPublishesSnapshotOrTouchesLiveFiles() throws Exception {
        Path f=flavor();write(f.resolve("Interface/file"),"live");
        try(ClientDb db=db()) {
            assertThrows(java.io.InterruptedIOException.class,()->Backups.create(db,f,"test",null,null,true,true,()->true));
            assertEquals("live",Files.readString(f.resolve("Interface/file")));assertTrue(Backups.list(db,f).isEmpty());
        }
    }
    @Test void corruptedSnapshotRejectedBeforeRestoreChangesLiveFiles() throws Exception {
        Path f=flavor();write(f.resolve("Interface/file"),"live");
        try(ClientDb db=db()) {
            var b=Backups.create(db,f,"test",null,null,true,false,()->false);
            write(f.resolve(".wus-backups/"+b.suffix()+"/Interface/file"),"corrupt");
            assertThrows(Exception.class,()->Backups.restore(db,f,"forever",b,null));assertEquals("live",Files.readString(f.resolve("Interface/file")));
        }
    }
    @Test void absentFoldersBackedAsAbsentAndRestoredWithoutMixing() throws Exception {
        Path f=flavor();
        try(ClientDb db=db()) {
            var b=Backups.create(db,f,"test",null,null,true,true,()->false);
            write(f.resolve("Interface/file"),"later");write(f.resolve("WTF/file"),"later");
            Backups.restore(db,f,"forever",b,null);
            assertFalse(Files.exists(f.resolve("Interface")));assertFalse(Files.exists(f.resolve("WTF")));
        }
    }
    @Test void insufficientSpaceFailsBeforeCopy() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"));
        Path f=flavor(),sparse=f.resolve("Interface/huge-sparse-file");Files.createDirectories(sparse.getParent());
        try(var file=new java.io.RandomAccessFile(sparse.toFile(),"rw")){file.setLength(FileOps.usableSpace(f)+(128L<<20));}
        try(ClientDb db=db()) {
            assertThrows(io.github.azotorp.wowuisync.client.util.UserException.class,()->Backups.create(db,f,"test",null,null,true,false,()->false));
            assertTrue(Backups.list(db,f).isEmpty());
        } finally {Files.deleteIfExists(sparse);}
    }
    @Test void deniedFileFailsScanWithoutPartialManifest() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(dir).supportsFileAttributeView("posix"));
        Path f=flavor(),file=f.resolve("Interface/AddOns/Example/denied.lua");write(file,"private synthetic test");
        var old=Files.getPosixFilePermissions(file);
        try(ClientDb db=db()) {
            Files.setPosixFilePermissions(file,Set.of());
            assertThrows(java.io.IOException.class,()->Scanner.scan(f,new Exclusions(List.of()),db,null,()->false));
        } finally {Files.setPosixFilePermissions(file,old);}
    }
    @Test void symlinkCannotSilentlyBecomeEmptyPushSource() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"));
        Path f=flavor();Path outside=dir.resolve("elsewhere");Files.createDirectories(outside);Files.createSymbolicLink(f.resolve("WTF"),outside);
        try(ClientDb db=db()){assertThrows(java.io.IOException.class,()->Scanner.scan(f,new Exclusions(List.of()),db,null,()->false));}
    }
}
