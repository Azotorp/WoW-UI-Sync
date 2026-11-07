package io.github.azotorp.wowuisync.server.security;

import io.github.azotorp.wowuisync.common.crypto.CertFingerprint;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;

/** Self-signed TLS key and certificate in {@code tls/keystore.p12}. */
public final class TlsKeys {
    /** The keystore is protected by file permissions; this password only satisfies the PKCS#12 format. */
    static final char[] STORE_PASSWORD = "wow-ui-sync".toCharArray();
    static final String ALIAS = "server";

    private TlsKeys() {}

    public static void generate(Path keystore, String host) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        KeyPair kp = g.generateKeyPair();
        String cn = host == null || host.isBlank() ? "wow-ui-sync" : host.replaceAll("[,=+<>#;\"\\\\]", "");
        X500Name name = new X500Name("CN=" + cn + ",O=WoW UI Sync");
        long now = System.currentTimeMillis();
        X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(name,
                new BigInteger(64, new SecureRandom()).abs().add(BigInteger.ONE),
                new Date(now - 86_400_000L), new Date(now + 30L * 365 * 86_400_000L), name, kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(b.build(signer));
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(ALIAS, kp.getPrivate(), STORE_PASSWORD, new Certificate[] {cert});
        Files.createDirectories(keystore.getParent());
        try (OutputStream out = Files.newOutputStream(keystore)) {
            ks.store(out, STORE_PASSWORD);
        }
    }

    public static KeyStore load(Path keystore) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            ks.load(in, STORE_PASSWORD);
        }
        return ks;
    }

    public static String fingerprint(KeyStore ks) throws Exception {
        return CertFingerprint.of(ks.getCertificate(ALIAS));
    }

    public static SSLContext serverContext(KeyStore ks) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, STORE_PASSWORD);
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        return ctx;
    }
}
