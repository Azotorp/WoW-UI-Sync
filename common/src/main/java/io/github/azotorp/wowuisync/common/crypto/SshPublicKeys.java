package io.github.azotorp.wowuisync.common.crypto;

import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.RSAKeyParameters;
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** OpenSSH public keys accepted for login (DESIGN.md §7): ssh-ed25519, ssh-rsa ≥ 2048, ecdsa-sha2-nistp256. */
public final class SshPublicKeys {
    public static final int MAX_LINE = 16 * 1024;

    /** A validated key: canonical line ({@code type base64 [comment]}), key type, and JDK public key. */
    public record ParsedKey(String type, String line, PublicKey publicKey, int bits) {
        public String jdkSignatureAlgorithm() {
            return switch (type) {
                case "ssh-ed25519" -> "Ed25519";
                case "ssh-rsa" -> "SHA256withRSA";
                default -> "SHA256withECDSA";
            };
        }
    }

    private SshPublicKeys() {}

    /**
     * Parses and validates one public key line.
     * @throws IllegalArgumentException with a user-facing message on any problem.
     */
    public static ParsedKey parse(String line) {
        if (line == null) throw new IllegalArgumentException("Paste a public key line.");
        String l = line.trim();
        if (l.isEmpty()) throw new IllegalArgumentException("Paste a public key line.");
        if (l.length() > MAX_LINE) throw new IllegalArgumentException("That key is too long.");
        if (l.contains("PRIVATE KEY")) throw new IllegalArgumentException("That is a private key. Paste the public key (the .pub file) instead.");
        if (l.startsWith("---- BEGIN SSH2")) {
            throw new IllegalArgumentException("That is a PuTTY/SSH2 public key. In PuTTYgen, copy the line from "
                    + "'Public key for pasting into OpenSSH authorized_keys file'.");
        }
        String[] parts = l.split("\\s+", 3);
        if (parts.length < 2) throw new IllegalArgumentException("A public key line looks like 'ssh-ed25519 AAAA... comment'.");
        String type = parts[0];
        if (!type.equals("ssh-ed25519") && !type.equals("ssh-rsa") && !type.equals("ecdsa-sha2-nistp256")) {
            throw new IllegalArgumentException("Unsupported key type '" + type + "'. Use ssh-ed25519, ssh-rsa (2048 bits or more), or ecdsa-sha2-nistp256.");
        }
        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The key data is not valid base64 (was it cut off when copying?).");
        }
        String blobType = readBlobType(blob);
        if (!type.equals(blobType)) throw new IllegalArgumentException("The key data does not match its type (was it cut off when copying?).");
        AsymmetricKeyParameter p;
        try {
            p = OpenSSHPublicKeyUtil.parsePublicKey(blob);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The key could not be read completely (was it cut off when copying?).");
        }
        int bits;
        if (p instanceof RSAKeyParameters rsa) {
            bits = rsa.getModulus().bitLength();
            if (bits < 2048) throw new IllegalArgumentException("RSA keys must be at least 2048 bits (this one is " + bits + ").");
            if (bits > 16384) throw new IllegalArgumentException("RSA keys larger than 16384 bits are not supported.");
        } else if (p instanceof Ed25519PublicKeyParameters) {
            bits = 256;
        } else if (p instanceof ECPublicKeyParameters) {
            bits = 256;
        } else {
            throw new IllegalArgumentException("Unsupported key.");
        }
        PublicKey pk = toJdk(p);
        String canonical = type + " " + Base64.getEncoder().encodeToString(blob) + (parts.length > 2 ? " " + parts[2].trim() : "");
        return new ParsedKey(type, canonical, pk, bits);
    }

    static String readBlobType(byte[] blob) {
        if (blob.length < 4) throw new IllegalArgumentException("The key data is too short (was it cut off when copying?).");
        int len = ((blob[0] & 0xFF) << 24) | ((blob[1] & 0xFF) << 16) | ((blob[2] & 0xFF) << 8) | (blob[3] & 0xFF);
        if (len < 0 || len > 64 || 4 + len > blob.length) throw new IllegalArgumentException("The key data is corrupt.");
        return new String(blob, 4, len, StandardCharsets.US_ASCII);
    }

    public static PublicKey toJdk(AsymmetricKeyParameter p) {
        try {
            SubjectPublicKeyInfo spki = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(p);
            String alg = p instanceof RSAKeyParameters ? "RSA" : p instanceof Ed25519PublicKeyParameters ? "Ed25519" : "EC";
            return KeyFactory.getInstance(alg).generatePublic(new X509EncodedKeySpec(spki.getEncoded()));
        } catch (Exception e) {
            throw new IllegalArgumentException("The key could not be converted: " + e.getMessage(), e);
        }
    }

    public static boolean verify(ParsedKey key, byte[] data, byte[] signature) {
        try {
            Signature s = Signature.getInstance(key.jdkSignatureAlgorithm());
            s.initVerify(key.publicKey());
            s.update(data);
            return s.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }
}
