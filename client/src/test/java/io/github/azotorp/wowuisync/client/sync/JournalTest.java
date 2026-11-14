package io.github.azotorp.wowuisync.client.sync;
import io.github.azotorp.wowuisync.common.hash.Hashing;
import io.github.azotorp.wowuisync.common.manifest.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class JournalTest {
 @TempDir Path dir;
 private ManifestEntry entry(String path,String text){return new ManifestEntry(path,text.length(),Hashing.sha256Hex(text.getBytes()));}
 private void write(Path file,String text)throws Exception{Files.createDirectories(file.getParent());Files.writeString(file,text);}
 @Test void transientChunksAreClearedUnlessRecoveryNeedsThem()throws Exception{
  var paths=new io.github.azotorp.wowuisync.client.data.ClientPaths(dir.resolve("data"));write(paths.stagedObjects().resolve("chunk"),"raw bytes");
  Path operations=paths.root().resolve("operations");write(operations.resolve("pending.json"),"journal");paths.cleanTransient();assertTrue(Files.exists(paths.stagedObjects().resolve("chunk")));
  Files.delete(operations.resolve("pending.json"));paths.cleanTransient();assertFalse(Files.exists(paths.stagedObjects()));
 }
 @Test void swapInputsAndReplayInterruptedWrites() throws Exception {
  Path wow=dir.resolve("wow"),data=dir.resolve("data"),chunks=data.resolve("partial/objects");
  var a=entry("WTF/a.lua","AAA");var b=entry("WTF/b.lua","BBB");
  write(wow.resolve(a.path()),"AAA");write(wow.resolve(b.path()),"BBB");
  write(chunks.resolve(a.sha256()),"AAA");write(chunks.resolve(b.sha256()),"BBB");
  var target=List.of(entry("WTF/a.lua","BBB"),entry("WTF/b.lua","AAA"));
  var op=new ApplyJournal.Operation("test",wow.toString(),"forever","host","source","owner","recovery",List.of(a,b),target,true);
  ApplyJournal.reconstruct(chunks,ApplyJournal.prepared(data,op),target);Path journal=ApplyJournal.journal(data,wow);ApplyJournal.write(journal,op);
  // A process died after the first replacement. Replay must use immutable inputs.
  write(wow.resolve("WTF/a.lua"),"BBB");ApplyJournal.apply(data,journal);ApplyJournal.apply(data,journal);
  assertEquals("BBB",Files.readString(wow.resolve("WTF/a.lua")));assertEquals("AAA",Files.readString(wow.resolve("WTF/b.lua")));
  assertTrue(Files.exists(journal));ApplyJournal.finish(data,journal);assertFalse(Files.exists(journal));
 }
 @Test void corruptStagingAndChangedDestinationLeaveJournal() throws Exception {
  Path wow=dir.resolve("wow"),data=dir.resolve("data"),chunks=data.resolve("chunks");var old=entry("WTF/a.lua","old");var next=entry("WTF/a.lua","new");
  write(wow.resolve(old.path()),"old");write(chunks.resolve(next.sha256()),"BAD");
  assertThrows(java.io.IOException.class,()->ApplyJournal.reconstruct(chunks,data.resolve("prepared"),List.of(next)));
  assertEquals("old",Files.readString(wow.resolve(old.path())));
  write(chunks.resolve(next.sha256()),"new");var op=new ApplyJournal.Operation("test",wow.toString(),"forever","host","src","owner","recovery",List.of(old),List.of(next),true);
  ApplyJournal.reconstruct(chunks,ApplyJournal.prepared(data,op),List.of(next));Path file=ApplyJournal.journal(data,wow);ApplyJournal.write(file,op);
  write(wow.resolve(old.path()),"user edit");assertThrows(java.io.IOException.class,()->ApplyJournal.apply(data,file));assertTrue(Files.exists(file));assertEquals("user edit",Files.readString(wow.resolve(old.path())));
 }
}
