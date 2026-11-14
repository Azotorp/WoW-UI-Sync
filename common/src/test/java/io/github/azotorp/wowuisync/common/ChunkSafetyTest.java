package io.github.azotorp.wowuisync.common;
import io.github.azotorp.wowuisync.common.chunk.*;
import io.github.azotorp.wowuisync.common.codec.Codec;
import io.github.azotorp.wowuisync.common.hash.Hashing;
import io.github.azotorp.wowuisync.common.path.SyncPaths;
import io.github.azotorp.wowuisync.common.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ChunkSafetyTest {
 @TempDir Path dir;
 @Test void deterministicChunksReuseAfterInsertion() throws Exception {
  byte[] original=new byte[24<<20];new Random(42).nextBytes(original);
  ByteArrayOutputStream joined=new ByteArrayOutputStream();
  var first=ContentChunker.split(new ByteArrayInputStream(original),(r,b)->{assertEquals(r.hash(),Hashing.sha256Hex(b));assertTrue(r.size()<=ContentChunker.MAX);joined.write(b);});
  assertArrayEquals(original,joined.toByteArray());
  var repeat=ContentChunker.split(new ByteArrayInputStream(original),(r,b)->{});assertEquals(first,repeat);
  byte[] changed=new byte[original.length+97];System.arraycopy(original,0,changed,97,original.length);
  var next=ContentChunker.split(new ByteArrayInputStream(changed),(r,b)->{});
  Set<String> hashes=new HashSet<>();for(var r:first)hashes.add(r.hash());
  assertTrue(next.stream().filter(x->hashes.contains(x.hash())).mapToLong(ChunkRef::size).sum()>original.length/2);
 }
 @Test void emptyAndSmallChunks() throws Exception {
  var refs=ContentChunker.split(new ByteArrayInputStream(new byte[0]),(r,b)->assertEquals(0,b.length));assertEquals(1,refs.size());
  assertEquals(Hashing.sha256Hex(new byte[0]),refs.getFirst().hash());
 }
 @Test void decompressionBombIsBounded() {
  byte[] compressed=Codec.DEFLATE.compress(new byte[33<<20]);assertThrows(RuntimeException.class,()->Codec.DEFLATE.decompress(compressed,-1));
  assertThrows(RuntimeException.class,()->Codec.ZSTD.decompress(new byte[0],Long.MAX_VALUE));
 }
 @Test void malformedFrameLengthIsRejected() throws Exception {
  var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeByte(1);out.writeInt(1);out.writeInt(Integer.MAX_VALUE);
  try(Wire wire=new Wire(new ByteArrayInputStream(bytes.toByteArray()),new ByteArrayOutputStream(),()->{})){assertThrows(IOException.class,wire::read);}
 }
 @Test void pathRulesRejectEscapesReservedAndSymlinks() throws Exception {
  assertThrows(IOException.class,()->SyncPaths.resolve(dir,"WTF/../escape"));assertThrows(IOException.class,()->SyncPaths.resolve(dir,"WTF/CON.lua"));
  org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name","").toLowerCase().contains("win"),"Windows junction validation needs a native fixture");
  Path outside=Files.createDirectory(dir.resolve("outside")),root=Files.createDirectory(dir.resolve("wow"));Files.createSymbolicLink(root.resolve("WTF"),outside);
  assertThrows(IOException.class,()->SyncPaths.resolve(root,"WTF/file.lua"));
 }
 @Test void ancestorAliasIsAllowedButLinksInsideInstallationAreRejected() throws Exception {
  Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"));
  Path real=Files.createDirectory(dir.resolve("real"));
  Path root=Files.createDirectory(real.resolve("wow"));
  Files.createDirectories(root.resolve("WTF"));
  Files.writeString(root.resolve("WTF/settings.lua"),"saved=true");
  Path alias=dir.resolve("alias");Files.createSymbolicLink(alias,real);
  Path selected=alias.resolve("wow");
  assertEquals(selected.resolve("WTF/settings.lua").toAbsolutePath(),SyncPaths.resolve(selected,"WTF/settings.lua"));
  assertEquals(selected.resolve("WTF/new.lua").toAbsolutePath(),SyncPaths.resolve(selected,"WTF/new.lua"));
  Path outside=Files.createDirectory(dir.resolve("outside-alias"));
  Files.createSymbolicLink(root.resolve("WTF/link"),outside);
  assertThrows(IOException.class,()->SyncPaths.resolve(selected,"WTF/link/settings.lua"));

 }

}
