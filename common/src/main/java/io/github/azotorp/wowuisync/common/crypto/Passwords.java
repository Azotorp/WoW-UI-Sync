package io.github.azotorp.wowuisync.common.crypto;

import io.github.azotorp.wowuisync.common.Defaults;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** PBKDF2-HMAC-SHA256 password hashing (DESIGN.md §7). */
public final class Passwords {
    public static final SecureRandom RANDOM = new SecureRandom();

    public record Hash(String hashB64, String saltB64, int iterations) {}

    private Passwords() {}

    public static Hash hash(char[] password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        int it = Defaults.PBKDF2_ITERATIONS;
        return new Hash(Base64.getEncoder().encodeToString(derive(password, salt, it)),
                Base64.getEncoder().encodeToString(salt), it);
    }

    public static boolean verify(char[] password, String hashB64, String saltB64, int iterations) {
        if(password==null || password.length>1024 || iterations<100_000 || iterations>2_000_000) return false;
        byte[] expected = Base64.getDecoder().decode(hashB64);
        byte[] actual = derive(password, Base64.getDecoder().decode(saltB64), iterations);
        return MessageDigest.isEqual(expected, actual);
    }

    private static final java.util.concurrent.Semaphore HASH_JOBS=new java.util.concurrent.Semaphore(4);
    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        if(!HASH_JOBS.tryAcquire()) throw new IllegalStateException("Authentication worker budget exhausted");
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally { HASH_JOBS.release(); }
    }

    public static String validationMessage(char[] pw) {
        if (pw == null || pw.length < Defaults.MIN_PASSWORD_LENGTH) {
            return "Passwords must be at least " + Defaults.MIN_PASSWORD_LENGTH + " characters.";
        }
        if(pw.length>1024) return "Password is too long.";
        return null;
    }

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    public static String randomToken(int bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(bytes));
    }
}
