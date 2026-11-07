package io.github.azotorp.wowuisync.client.crypto;

import org.bouncycastle.crypto.engines.BlowfishEngine;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * OpenBSD {@code bcrypt_pbkdf}, used by OpenSSH to derive the key that encrypts private key files.
 * The Blowfish initial tables (digits of pi) are taken from BouncyCastle's {@link BlowfishEngine}.
 */
final class BcryptPbkdf {
    private static final int[] KP;
    private static final int[][] KS;

    static {
        try {
            KP = table("KP");
            KS = new int[][] {table("KS0"), table("KS1"), table("KS2"), table("KS3")};
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static int[] table(String name) throws ReflectiveOperationException {
        Field f = BlowfishEngine.class.getDeclaredField(name);
        f.setAccessible(true);
        return ((int[]) f.get(null)).clone();
    }

    private final int[] p = new int[18];
    private final int[][] s = new int[4][256];

    private BcryptPbkdf() {}

    static byte[] derive(byte[] password, byte[] salt, int keyLen, int rounds) throws Exception {
        if (rounds < 1 || keyLen < 1 || keyLen > 1024 || salt.length == 0) throw new IllegalArgumentException("Bad KDF parameters");
        MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
        byte[] sha2pass = sha512.digest(password);
        int stride = (keyLen + 31) / 32;
        int amt = (keyLen + stride - 1) / stride;
        byte[] key = new byte[keyLen];
        int remaining = keyLen;
        byte[] countSalt = new byte[salt.length + 4];
        System.arraycopy(salt, 0, countSalt, 0, salt.length);
        for (int count = 1; remaining > 0; count++) {
            countSalt[salt.length] = (byte) (count >>> 24);
            countSalt[salt.length + 1] = (byte) (count >>> 16);
            countSalt[salt.length + 2] = (byte) (count >>> 8);
            countSalt[salt.length + 3] = (byte) count;
            byte[] sha2salt = sha512.digest(countSalt);
            byte[] tmp = new BcryptPbkdf().hash(sha2pass, sha2salt);
            byte[] out = tmp.clone();
            for (int i = 1; i < rounds; i++) {
                sha2salt = sha512.digest(tmp);
                tmp = new BcryptPbkdf().hash(sha2pass, sha2salt);
                for (int j = 0; j < out.length; j++) out[j] ^= tmp[j];
            }
            int a = Math.min(amt, remaining);
            int i;
            for (i = 0; i < a; i++) {
                int dest = i * stride + (count - 1);
                if (dest >= keyLen) break;
                key[dest] = out[i];
            }
            remaining -= i;
        }
        return key;
    }

    private byte[] hash(byte[] sha2pass, byte[] sha2salt) {
        System.arraycopy(KP, 0, p, 0, 18);
        for (int i = 0; i < 4; i++) System.arraycopy(KS[i], 0, s[i], 0, 256);
        expandState(sha2salt, sha2pass);
        for (int i = 0; i < 64; i++) {
            expand0State(sha2salt);
            expand0State(sha2pass);
        }
        byte[] ct = "OxychromaticBlowfishSwatDynamite".getBytes(StandardCharsets.US_ASCII);
        int[] cdata = new int[8];
        int[] pos = {0};
        for (int i = 0; i < 8; i++) cdata[i] = streamToWord(ct, pos);
        for (int i = 0; i < 64; i++) {
            for (int j = 0; j < 8; j += 2) {
                long r = encipher(cdata[j], cdata[j + 1]);
                cdata[j] = (int) (r >>> 32);
                cdata[j + 1] = (int) r;
            }
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[4 * i + 3] = (byte) (cdata[i] >>> 24);
            out[4 * i + 2] = (byte) (cdata[i] >>> 16);
            out[4 * i + 1] = (byte) (cdata[i] >>> 8);
            out[4 * i] = (byte) cdata[i];
        }
        return out;
    }

    private static int streamToWord(byte[] data, int[] pos) {
        int w = 0;
        for (int i = 0; i < 4; i++) {
            if (pos[0] >= data.length) pos[0] = 0;
            w = (w << 8) | (data[pos[0]++] & 0xFF);
        }
        return w;
    }

    private int f(int x) {
        return ((s[0][x >>> 24] + s[1][(x >>> 16) & 0xFF]) ^ s[2][(x >>> 8) & 0xFF]) + s[3][x & 0xFF];
    }

    /** Returns (left << 32) | right. */
    private long encipher(int xl, int xr) {
        int l = xl ^ p[0];
        int r = xr;
        for (int i = 1; i <= 16; i += 2) {
            r ^= f(l) ^ p[i];
            l ^= f(r) ^ p[i + 1];
        }
        int outL = r ^ p[17];
        int outR = l;
        return ((long) outL << 32) | (outR & 0xFFFFFFFFL);
    }

    private void expandState(byte[] data, byte[] key) {
        int[] j = {0};
        for (int i = 0; i < 18; i++) p[i] ^= streamToWord(key, j);
        j[0] = 0;
        int l = 0, r = 0;
        for (int i = 0; i < 18; i += 2) {
            l ^= streamToWord(data, j);
            r ^= streamToWord(data, j);
            long e = encipher(l, r);
            l = (int) (e >>> 32);
            r = (int) e;
            p[i] = l;
            p[i + 1] = r;
        }
        for (int b = 0; b < 4; b++) {
            for (int k = 0; k < 256; k += 2) {
                l ^= streamToWord(data, j);
                r ^= streamToWord(data, j);
                long e = encipher(l, r);
                l = (int) (e >>> 32);
                r = (int) e;
                s[b][k] = l;
                s[b][k + 1] = r;
            }
        }
    }

    private void expand0State(byte[] key) {
        int[] j = {0};
        for (int i = 0; i < 18; i++) p[i] ^= streamToWord(key, j);
        int l = 0, r = 0;
        for (int i = 0; i < 18; i += 2) {
            long e = encipher(l, r);
            l = (int) (e >>> 32);
            r = (int) e;
            p[i] = l;
            p[i + 1] = r;
        }
        for (int b = 0; b < 4; b++) {
            for (int k = 0; k < 256; k += 2) {
                long e = encipher(l, r);
                l = (int) (e >>> 32);
                r = (int) e;
                s[b][k] = l;
                s[b][k + 1] = r;
            }
        }
    }
}
