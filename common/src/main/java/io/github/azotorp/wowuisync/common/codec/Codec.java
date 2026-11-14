package io.github.azotorp.wowuisync.common.codec;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Compression codecs (DESIGN.md §3, §11.5). zstd is preferred; JDK deflate is the fallback when the zstd native
 * library cannot be loaded on either side.
 */
public enum Codec {
    RAW(0, "raw"),
    ZSTD(1, "zstd"),
    DEFLATE(2, "deflate");

    public static final int DEFAULT_LEVEL = 3;

    private static final boolean ZSTD_AVAILABLE = probeZstd();

    private final int id;
    private final String wireName;

    Codec(int id, String wireName) {
        this.id = id;
        this.wireName = wireName;
    }

    public int id() {
        return id;
    }

    public String wireName() {
        return wireName;
    }

    private static boolean probeZstd() {
        try {
            byte[] c = Zstd.compress(new byte[] {1, 2, 3}, 1);
            return Zstd.decompress(c, 3).length == 3;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean zstdAvailable() {
        return ZSTD_AVAILABLE;
    }

    /** The best locally available compressing codec. */
    public static Codec preferred() {
        return ZSTD_AVAILABLE ? ZSTD : DEFLATE;
    }

    public static Codec byId(int id) {
        for (Codec c : values()) if (c.id == id) return c;
        throw new IllegalArgumentException("Unknown codec id " + id);
    }

    public static Codec byName(String name) {
        for (Codec c : values()) if (c.wireName.equalsIgnoreCase(name)) return c;
        throw new IllegalArgumentException("Unknown codec " + name);
    }

    /** Codec names this side supports, most preferred first. */
    public static List<String> supported() {
        List<String> l = new ArrayList<>();
        if (ZSTD_AVAILABLE) l.add(ZSTD.wireName);
        l.add(DEFLATE.wireName);
        l.add(RAW.wireName);
        return l;
    }

    /** zstd if both sides support it, else deflate. */
    public static Codec negotiate(Collection<String> peer) {
        if (ZSTD_AVAILABLE && peer != null && peer.contains(ZSTD.wireName)) return ZSTD;
        return DEFLATE;
    }

    public byte[] compress(byte[] data) {
        return compress(data, 0, data.length, DEFAULT_LEVEL);
    }

    public byte[] compress(byte[] data, int off, int len, int level) {
        switch (this) {
            case RAW: {
                byte[] out = new byte[len];
                System.arraycopy(data, off, out, 0, len);
                return out;
            }
            case ZSTD: {
                byte[] in = data;
                if (off != 0 || len != data.length) {
                    in = new byte[len];
                    System.arraycopy(data, off, in, 0, len);
                }
                return Zstd.compress(in, level);
            }
            default: {
                Deflater d = new Deflater(level <= 1 ? Deflater.BEST_SPEED : 6);
                try {
                    d.setInput(data, off, len);
                    d.finish();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, len / 2));
                    byte[] buf = new byte[65536];
                    while (!d.finished()) {
                        int n = d.deflate(buf);
                        bos.write(buf, 0, n);
                    }
                    return bos.toByteArray();
                } finally {
                    d.end();
                }
            }
        }
    }

    /** Decompresses {@code data}. {@code originalSize} may be -1 if unknown. */
    public byte[] decompress(byte[] data, long originalSize) {
        final int limit = 32 << 20;
        if (originalSize > limit || originalSize < -1) throw new IllegalArgumentException("Decoded payload too large");
        try (InputStream in = wrapIn(new java.io.ByteArrayInputStream(data));
             ByteArrayOutputStream out = new ByteArrayOutputStream((int)Math.min(65536, Math.max(0, originalSize)))) {
            byte[] buf = new byte[65536]; int n;
            while ((n=in.read(buf))!=-1) {
                if ((long)out.size()+n>limit || (originalSize>=0 && (long)out.size()+n>originalSize))
                    throw new IOException("Decoded payload exceeds limit");
                out.write(buf,0,n);
            }
            if(originalSize>=0 && out.size()!=originalSize) throw new IOException("Decoded length mismatch");
            return out.toByteArray();
        } catch(IOException e) { throw new UncheckedIOException(e); }
    }

    public OutputStream wrapOut(OutputStream out, int level) throws IOException {
        return switch (this) {
            case RAW -> out;
            case ZSTD -> new ZstdOutputStream(out, level);
            case DEFLATE -> new DeflaterOutputStream(out, new Deflater(6), 65536);
        };
    }

    public InputStream wrapIn(InputStream in) {
        try {
            return switch (this) {
                case RAW -> in;
                case ZSTD -> new ZstdInputStream(in);
                case DEFLATE -> new InflaterInputStream(in, new Inflater(), 65536);
            };
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One codec-id byte followed by the compressed data. */
    public byte[] encodeTagged(byte[] data) {
        byte[] body = compress(data);
        byte[] out = new byte[body.length + 1];
        out[0] = (byte) id;
        System.arraycopy(body, 0, out, 1, body.length);
        return out;
    }

    public static byte[] decodeTagged(byte[] data) {
        if (data.length == 0) throw new IllegalArgumentException("Empty tagged data");
        Codec c = byId(data[0] & 0xFF);
        byte[] body = new byte[data.length - 1];
        System.arraycopy(data, 1, body, 0, body.length);
        return c.decompress(body, -1);
    }

    private static byte[] readAll(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
