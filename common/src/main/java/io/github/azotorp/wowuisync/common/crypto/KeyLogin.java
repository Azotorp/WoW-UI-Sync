package io.github.azotorp.wowuisync.common.crypto;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Key login challenge data (DESIGN.md §7): {@code nonce || sessionId || serverCertFingerprint}. */
public final class KeyLogin {
    private KeyLogin() {}

    public static byte[] signedData(String nonceB64, String sessionId, String serverCertFingerprint) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(Base64.getDecoder().decode(nonceB64));
        b.writeBytes(sessionId.getBytes(StandardCharsets.UTF_8));
        b.writeBytes(serverCertFingerprint.getBytes(StandardCharsets.UTF_8));
        return b.toByteArray();
    }
}
