package io.github.azotorp.wowuisync.client.crypto;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.crypto.CryptoException;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.RSAKeyParameters;
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.crypto.signers.RSADigestSigner;
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

/**
 * Loads OpenSSH private key files (openssh-key-v1, encrypted or not, and PEM/PKCS#8) and signs login challenges.
 * The private key never leaves the client.
 */
public final class PrivateKeys {
    /** Thrown when the key file is encrypted and no (or a wrong) passphrase was given. */
    public static final class PassphraseNeeded extends Exception {
        private final boolean wrong;

        PassphraseNeeded(boolean wrong) {
            super(wrong ? "Wrong passphrase." : "This key file is protected by a passphrase.");
            this.wrong = wrong;
        }

        public boolean wrong() {
            return wrong;
        }
    }

    /** A loaded private key. */
    public record LoadedKey(AsymmetricKeyParameter privateKey, String type) {
        /** Signs data in the format the server verifies with JDK {@code Signature}. */
        public byte[] sign(byte[] data) throws CryptoException {
            if (privateKey instanceof Ed25519PrivateKeyParameters ed) {
                Ed25519Signer s = new Ed25519Signer();
                s.init(true, ed);
                s.update(data, 0, data.length);
                return s.generateSignature();
            }
            if (privateKey instanceof RSAKeyParameters rsa) {
                RSADigestSigner s = new RSADigestSigner(new SHA256Digest());
                s.init(true, rsa);
                s.update(data, 0, data.length);
                return s.generateSignature();
            }
            if (privateKey instanceof ECPrivateKeyParameters ec) {
                SHA256Digest d = new SHA256Digest();
                d.update(data, 0, data.length);
                byte[] h = new byte[32];
                d.doFinal(h, 0);
                ECDSASigner s = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
                s.init(true, ec);
                BigInteger[] rs = s.generateSignature(h);
                ASN1EncodableVector v = new ASN1EncodableVector();
                v.add(new ASN1Integer(rs[0]));
                v.add(new ASN1Integer(rs[1]));
                try {
                    return new DERSequence(v).getEncoded();
                } catch (IOException e) {
                    throw new CryptoException("Encoding failed", e);
                }
            }
            throw new CryptoException("Unsupported key type");
        }

        /** The matching OpenSSH public key line ({@code type base64}). */
        public String publicKeyLine() throws IOException {
            AsymmetricKeyParameter pub;
            if (privateKey instanceof Ed25519PrivateKeyParameters ed) pub = ed.generatePublicKey();
            else if (privateKey instanceof RSAPrivateCrtKeyParameters rsa) pub = new RSAKeyParameters(false, rsa.getModulus(), rsa.getPublicExponent());
            else if (privateKey instanceof ECPrivateKeyParameters ec) {
                ECDomainParameters p = ec.getParameters();
                pub = new ECPublicKeyParameters(p.getG().multiply(ec.getD()).normalize(), p);
            } else throw new IOException("Unsupported key type");
            return type + " " + Base64.getEncoder().encodeToString(OpenSSHPublicKeyUtil.encodePublicKey(pub));
        }
    }

    private static final java.security.Provider BC = new org.bouncycastle.jce.provider.BouncyCastleProvider();
    private static final byte[] MAGIC = "openssh-key-v1\0".getBytes(StandardCharsets.US_ASCII);

    private PrivateKeys() {}

    public static LoadedKey load(Path file, char[] passphrase) throws IOException, PassphraseNeeded {
        if (Files.size(file) > 64 * 1024) throw new IOException("That file is too large to be a private key.");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return parse(text, passphrase);
    }

    public static LoadedKey parse(String text, char[] passphrase) throws IOException, PassphraseNeeded {
        if (text.contains("PuTTY-User-Key-File")) {
            throw new IOException("This is a PuTTY .ppk file. In PuTTYgen, use Conversions > Export OpenSSH key and choose that file.");
        }
        if (text.contains("BEGIN OPENSSH PRIVATE KEY")) return parseOpenSsh(text, passphrase);
        if (text.contains("PRIVATE KEY")) return parsePem(text, passphrase);
        throw new IOException("This file is not a private key. Choose your OpenSSH private key file (not the .pub file).");
    }

    private static LoadedKey parseOpenSsh(String text, char[] passphrase) throws IOException, PassphraseNeeded {
        String b64 = text.replaceAll("-----[^-]+-----", "").replaceAll("\\s", "");
        byte[] blob = Base64.getDecoder().decode(b64);
        if (blob.length < MAGIC.length || !Arrays.equals(Arrays.copyOf(blob, MAGIC.length), MAGIC)) {
            throw new IOException("Unrecognized OpenSSH key format.");
        }
        ByteBuffer b = ByteBuffer.wrap(blob, MAGIC.length, blob.length - MAGIC.length);
        String cipher = str(b);
        String kdf = str(b);
        byte[] kdfOpts = bytes(b);
        int n = b.getInt();
        if (n != 1) throw new IOException("Key files with more than one key are not supported.");
        bytes(b); // public key (repeated inside the private section)
        byte[] priv = bytes(b);
        byte[] tag = null;
        if (!"none".equals(cipher)) {
            if (passphrase == null || passphrase.length == 0) throw new PassphraseNeeded(false);
            if (!"bcrypt".equals(kdf)) throw new IOException("Unsupported key derivation '" + kdf + "'.");
            ByteBuffer ko = ByteBuffer.wrap(kdfOpts);
            byte[] salt = bytes(ko);
            int rounds = ko.getInt();
            if (cipher.equals("aes256-gcm@openssh.com")) {
                tag = new byte[16];
                b.get(tag);
            }
            priv = decrypt(cipher, priv, tag, passphrase, salt, rounds);
        }
        return wrap(parsePrivateSection(priv));
    }

    /** Parses the (decrypted) private section of an openssh-key-v1 file. */
    private static AsymmetricKeyParameter parsePrivateSection(byte[] priv) throws IOException, PassphraseNeeded {
        ByteBuffer b = ByteBuffer.wrap(priv);
        if (b.getInt() != b.getInt()) throw new PassphraseNeeded(true);
        String type = str(b);
        switch (type) {
            case "ssh-ed25519" -> {
                bytes(b);
                byte[] sk = bytes(b);
                if (sk.length != 64) throw new IOException("Corrupt Ed25519 key.");
                return new Ed25519PrivateKeyParameters(sk, 0);
            }
            case "ssh-rsa" -> {
                BigInteger n = mpint(b), e = mpint(b), d = mpint(b), iqmp = mpint(b), p = mpint(b), q = mpint(b);
                return new RSAPrivateCrtKeyParameters(n, e, d, p, q, d.mod(p.subtract(BigInteger.ONE)),
                        d.mod(q.subtract(BigInteger.ONE)), iqmp);
            }
            case "ecdsa-sha2-nistp256" -> {
                String curve = str(b);
                if (!curve.equals("nistp256")) throw new IOException("Only nistp256 ECDSA keys are supported.");
                bytes(b);
                BigInteger d = mpint(b);
                org.bouncycastle.asn1.x9.X9ECParameters x9 = org.bouncycastle.asn1.nist.NISTNamedCurves.getByName("P-256");
                ECDomainParameters dom = new org.bouncycastle.crypto.params.ECNamedDomainParameters(
                        org.bouncycastle.asn1.sec.SECObjectIdentifiers.secp256r1, x9);
                return new ECPrivateKeyParameters(d, dom);
            }
            default -> throw new IOException("Unsupported key type '" + type + "'. Use ssh-ed25519, ssh-rsa, or ecdsa-sha2-nistp256.");
        }
    }

    private static BigInteger mpint(ByteBuffer b) throws IOException {
        return new BigInteger(bytes(b));
    }


    private static byte[] decrypt(String cipher, byte[] data, byte[] tag, char[] passphrase, byte[] salt, int rounds) throws IOException, PassphraseNeeded {
        int keyLen;
        int ivLen;
        String transform;
        switch (cipher) {
            case "aes128-ctr" -> { keyLen = 16; ivLen = 16; transform = "AES/CTR/NoPadding"; }
            case "aes192-ctr" -> { keyLen = 24; ivLen = 16; transform = "AES/CTR/NoPadding"; }
            case "aes256-ctr" -> { keyLen = 32; ivLen = 16; transform = "AES/CTR/NoPadding"; }
            case "aes128-cbc" -> { keyLen = 16; ivLen = 16; transform = "AES/CBC/NoPadding"; }
            case "aes192-cbc" -> { keyLen = 24; ivLen = 16; transform = "AES/CBC/NoPadding"; }
            case "aes256-cbc" -> { keyLen = 32; ivLen = 16; transform = "AES/CBC/NoPadding"; }
            case "aes256-gcm@openssh.com" -> { keyLen = 32; ivLen = 12; transform = "AES/GCM/NoPadding"; }
            default -> throw new IOException("Unsupported key encryption '" + cipher + "'. Re-encrypt the key with: ssh-keygen -p -Z aes256-ctr -f <file>");
        }
        try {
            byte[] pw = new String(passphrase).getBytes(StandardCharsets.UTF_8);
            byte[] km = BcryptPbkdf.derive(pw, salt, keyLen + ivLen, rounds);
            Arrays.fill(pw, (byte) 0);
            SecretKeySpec k = new SecretKeySpec(km, 0, keyLen, "AES");
            Cipher c = Cipher.getInstance(transform);
            if (transform.contains("GCM")) {
                c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, km, keyLen, ivLen));
                byte[] all = new byte[data.length + tag.length];
                System.arraycopy(data, 0, all, 0, data.length);
                System.arraycopy(tag, 0, all, data.length, tag.length);
                try {
                    return c.doFinal(all);
                } catch (javax.crypto.AEADBadTagException e) {
                    throw new PassphraseNeeded(true);
                }
            }
            c.init(Cipher.DECRYPT_MODE, k, new IvParameterSpec(km, keyLen, ivLen));
            return c.doFinal(data);
        } catch (PassphraseNeeded e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not decrypt the key: " + e.getMessage(), e);
        }
    }

    private static LoadedKey parsePem(String text, char[] passphrase) throws IOException, PassphraseNeeded {
        try (PEMParser p = new PEMParser(new StringReader(text))) {
            Object o = p.readObject();
            PrivateKeyInfo info;
            if (o instanceof PEMKeyPair kp) {
                info = kp.getPrivateKeyInfo();
            } else if (o instanceof PEMEncryptedKeyPair ekp) {
                if (passphrase == null || passphrase.length == 0) throw new PassphraseNeeded(false);
                try {
                    info = ekp.decryptKeyPair(new JcePEMDecryptorProviderBuilder().setProvider(BC).build(passphrase)).getPrivateKeyInfo();
                } catch (Exception e) {
                    throw new PassphraseNeeded(true);
                }
            } else if (o instanceof PrivateKeyInfo pki) {
                info = pki;
            } else if (o instanceof PKCS8EncryptedPrivateKeyInfo epki) {
                if (passphrase == null || passphrase.length == 0) throw new PassphraseNeeded(false);
                try {
                    info = epki.decryptPrivateKeyInfo(new JceOpenSSLPKCS8DecryptorProviderBuilder().setProvider(BC).build(passphrase));
                } catch (Exception e) {
                    throw new PassphraseNeeded(true);
                }
            } else {
                throw new IOException("Unrecognized private key file.");
            }
            return wrap(PrivateKeyFactory.createKey(info));
        }
    }

    private static LoadedKey wrap(AsymmetricKeyParameter key) throws IOException {
        if (key instanceof Ed25519PrivateKeyParameters) return new LoadedKey(key, "ssh-ed25519");
        if (key instanceof RSAPrivateCrtKeyParameters r) {
            if (r.getModulus().bitLength() < 2048) throw new IOException("RSA keys must be at least 2048 bits.");
            return new LoadedKey(key, "ssh-rsa");
        }
        if (key instanceof ECPrivateKeyParameters ec) {
            if (ec.getParameters().getCurve().getFieldSize() != 256) throw new IOException("Only nistp256 ECDSA keys are supported.");
            return new LoadedKey(key, "ecdsa-sha2-nistp256");
        }
        throw new IOException("Unsupported key type. Use ssh-ed25519, ssh-rsa (2048+), or ecdsa-sha2-nistp256.");
    }

    private static byte[] bytes(ByteBuffer b) throws IOException {
        int len = b.getInt();
        if (len < 0 || len > b.remaining()) throw new IOException("Corrupt key file.");
        byte[] out = new byte[len];
        b.get(out);
        return out;
    }

    private static String str(ByteBuffer b) throws IOException {
        return new String(bytes(b), StandardCharsets.US_ASCII);
    }

}
