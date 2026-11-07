package io.github.azotorp.wowuisync.common.hash;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers. */
public final class Hashing {
    private static final HexFormat HEX = HexFormat.of();

    private Hashing() {}

    public static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    public static byte[] unhex(String hex) {
        return HEX.parseHex(hex);
    }

    public static byte[] sha256(byte[] data) {
        return newSha256().digest(data);
    }

    public static byte[] sha256(byte[] data, int off, int len) {
        MessageDigest md = newSha256();
        md.update(data, off, len);
        return md.digest();
    }

    public static String sha256Hex(byte[] data) {
        return hex(sha256(data));
    }

    public static String sha256Hex(byte[] data, int off, int len) {
        return hex(sha256(data, off, len));
    }

    public static String sha256Hex(Path file) throws IOException {
        MessageDigest md = newSha256();
        byte[] buf = new byte[1 << 20];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    /** True for a 64-character lower-case hex string. */
    public static boolean isSha256Hex(String s) {
        if (s == null || s.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }
}
