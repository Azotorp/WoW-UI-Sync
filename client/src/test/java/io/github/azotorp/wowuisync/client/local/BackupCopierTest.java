package io.github.azotorp.wowuisync.client.local;
import io.github.azotorp.wowuisync.client.data.*;
import io.github.azotorp.wowuisync.client.sync.Scanner;
import io.github.azotorp.wowuisync.common.path.Exclusions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class BackupCopierTest {
    @TempDir Path temp;
    void write(Path p,String s) throws Exception {Files.createDirectories(p.getParent());Files.writeString(p,s);}
    @Test void nativeCommandCopiesFoldersWithoutMovingOrMirroring() {
        var command=BackupCopier.command(Path.of("robocopy.exe"),Path.of("source"),Path.of("target"),Path.of("copy.log"),8);
        assertTrue(command.contains("/E"));assertTrue(command.contains("/MT:8"));assertTrue(command.contains("/R:0"));
        assertFalse(command.contains("/MIR"));assertFalse(command.contains("/MOVE"));assertFalse(command.contains("/MOV"));
        assertTrue(BackupCopier.successfulExit(0));assertTrue(BackupCopier.successfulExit(7));assertFalse(BackupCopier.successfulExit(8));assertFalse(BackupCopier.successfulExit(16));
    }
    @Test void cachedSourcesAndExcludedFilesBothProduceRestorableSnapshots() throws Exception {
        Path root=temp.resolve("wow"),data=temp.resolve("client");
        write(root.resolve("Interface/AddOns/Example/file.lua"),"expected");write(root.resolve("Interface/empty/.keep"),"empty");
        write(root.resolve("WTF/Config.wtf"),"excluded but backed up");Files.createDirectories(root.resolve("Interface/actually empty"));
        try(ClientDb db=ClientDb.open(data.resolve("client.db"),data.resolve("backups"))) {
            Scanner.scan(root,Exclusions.defaults(),db,null,()->false);
            List<String> phases=Collections.synchronizedList(new ArrayList<>());
            var progress=new Backups.Progress(){public void update(String phase,long d,long t){}public void file(String phase,String path,long d,long t,long b,long total){phases.add(phase);assertFalse(path.contains("\\"));}};
            var backup=Backups.create(db,root,"test",null,progress,true,true,()->false);
            assertTrue(phases.contains("Verifying backup"));
            Path snapshot=root.resolve(".wus-backups").resolve(backup.suffix());
            assertTrue(Files.isDirectory(snapshot.resolve("Interface/actually empty")));
            assertEquals("excluded but backed up",Files.readString(snapshot.resolve("WTF/Config.wtf")));
            write(root.resolve("Interface/AddOns/Example/file.lua"),"changed");Backups.restore(db,root,"forever",backup,null);
            assertEquals("expected",Files.readString(root.resolve("Interface/AddOns/Example/file.lua")));
        }
    }
    @Test void cancelDuringCopyOrVerificationNeverPublishesSnapshot() throws Exception {
        Path root=temp.resolve("wow"),data=temp.resolve("client");for(int i=0;i<40;i++)write(root.resolve("Interface/AddOns/Example/"+i+".lua"),"expected");
        AtomicBoolean canceled=new AtomicBoolean();
        try(ClientDb db=ClientDb.open(data.resolve("client.db"),data.resolve("backups"))) {
            var progress=new Backups.Progress(){public void update(String p,long d,long t){}public void file(String p,String path,long d,long t,long b,long total){if(d>0)canceled.set(true);}};
            assertThrows(java.io.InterruptedIOException.class,()->Backups.create(db,root,"test",null,progress,true,false,canceled::get));
            assertTrue(Backups.list(db,root).isEmpty());assertEquals("expected",Files.readString(root.resolve("Interface/AddOns/Example/0.lua")));
        }
    }
    @Test void changedSourceDuringNativeOrPortableCopyIsRejected() throws Exception {
        Path root=temp.resolve("wow"),stage=temp.resolve("stage"),data=temp.resolve("client");Path file=root.resolve("Interface/AddOns/Example/file.lua");write(file,"before");Files.createDirectories(stage);
        try(ClientDb db=ClientDb.open(data.resolve("client.db"),data.resolve("backups"))) {
            AtomicBoolean mutated=new AtomicBoolean();
            var progress=new Backups.Progress(){public void update(String p,long d,long t){}public void file(String p,String path,long d,long t,long b,long total){if(p.equals("Verifying backup")&&d==0&&mutated.compareAndSet(false,true))try{Files.writeString(file,"after contents changed");}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}};
            assertThrows(java.io.IOException.class,()->BackupCopier.copy(root,stage,true,false,db,progress,()->false,1,6));
        }
    }
    @Test void windowsUsesInstalledRobocopy() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"));
        assertNotNull(BackupCopier.nativeTool());
        Path root=temp.resolve("source"),target=temp.resolve("target");write(root.resolve("Unicode é/file.txt"),"native copy");
        BackupCopier.nativeCopy(BackupCopier.nativeTool(),root,target,temp.resolve("copy.log"),2,()->false);
        assertEquals("native copy",Files.readString(target.resolve("Unicode é/file.txt")));
    }
}
