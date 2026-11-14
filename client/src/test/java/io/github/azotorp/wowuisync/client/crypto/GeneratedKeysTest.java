package io.github.azotorp.wowuisync.client.crypto;
import io.github.azotorp.wowuisync.common.crypto.SshPublicKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class GeneratedKeysTest {
 @TempDir Path dir;
 @Test void generatedKeySignsAndHasPrivatePermissions() throws Exception {
  Path file=GeneratedKeys.create(dir.resolve("keys"));var key=PrivateKeys.load(file,null);byte[] challenge="hello".getBytes();
  assertTrue(SshPublicKeys.verify(SshPublicKeys.parse(key.publicKeyLine()),challenge,key.sign(challenge)));
  if(Files.getFileAttributeView(file,java.nio.file.attribute.PosixFileAttributeView.class)!=null)assertEquals("rw-------",java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
 }
}
