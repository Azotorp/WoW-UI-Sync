package io.github.azotorp.wowuisync.common.crypto;

import io.github.azotorp.wowuisync.common.hash.Hashing;

import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;

/** SHA-256 fingerprint of a certificate, lower-case hex without separators. */
public final class CertFingerprint {
    private CertFingerprint() {}

    public static String of(Certificate cert) {
        try {
            return Hashing.sha256Hex(cert.getEncoded());
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code AB:CD:...} form for display. */
    public static String pretty(String hex) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 2) {
            if (i > 0) sb.append(':');
            sb.append(hex, i, Math.min(i + 2, hex.length()));
        }
        return sb.toString().toUpperCase(java.util.Locale.ROOT);
    }

    public static String normalize(String s) {
        return s == null ? null : s.replace(":", "").replace(" ", "").toLowerCase(java.util.Locale.ROOT);
    }
}
