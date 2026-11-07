package io.github.azotorp.wowuisync.client.crypto;

import io.github.azotorp.wowuisync.common.crypto.SshPublicKeys;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/** Runs against key files in the directory named by the system property {@code wowuisync.testKeys}, if set. */
class PrivateKeysTest {
    @Test
    void loadsAndSigns() throws Exception {
        String dir = System.getProperty("wowuisync.testKeys");
        Assumptions.assumeTrue(dir != null);
        Path d = Paths.get(dir);
        for (String t : new String[] {"ed", "rsa", "ec"}) {
            SshPublicKeys.ParsedKey pub = SshPublicKeys.parse(Files.readString(d.resolve(t + ".pub")));
            byte[] data = "challenge".getBytes(StandardCharsets.UTF_8);
            for (String v : new String[] {"_plain", "_enc", "_pem"}) {
                Path f = d.resolve(t + v);
                if (!v.equals("_plain")) assertThrows(PrivateKeys.PassphraseNeeded.class, () -> PrivateKeys.load(f, null));
                if (!v.equals("_plain")) {
                    PrivateKeys.PassphraseNeeded e = assertThrows(PrivateKeys.PassphraseNeeded.class, () -> PrivateKeys.load(f, "wrong".toCharArray()));
                    assertTrue(e.wrong(), t + v);
                }
                PrivateKeys.LoadedKey k;
                try {
                    k = PrivateKeys.load(f, "secret pass".toCharArray());
                } catch (Exception e) {
                    throw new AssertionError(t + v, e);
                }
                assertTrue(SshPublicKeys.verify(pub, data, k.sign(data)), t + v);
                assertEquals(pub.line().split(" ")[1], k.publicKeyLine().split(" ")[1], t + v);
            }
        }
    }
}
