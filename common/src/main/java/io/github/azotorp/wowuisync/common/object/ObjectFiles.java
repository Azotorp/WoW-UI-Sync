package io.github.azotorp.wowuisync.common.object;

import io.github.azotorp.wowuisync.common.codec.Codec;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Object file format (DESIGN.md §9):
 * {@code "WUSO" | u8 version | u8 codec | u64 originalSize | body}.
 * Objects are stored under {@code objects/<sha[0..2]>/<sha[2..4]>/<sha256>}.
 */
public final class ObjectFiles {
    public static final byte[] MAGIC = {'W', 'U', 'S', 'O'};
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 4 + 1 + 1 + 8;

    public record Header(Codec codec, long originalSize) {}

    private ObjectFiles() {}

    public static Path shardPath(Path objectsRoot, String sha256) {
        if (!io.github.azotorp.wowuisync.common.hash.Hashing.isSha256Hex(sha256)) throw new IllegalArgumentException("Invalid object hash");
        return objectsRoot.resolve(sha256.substring(0, 2)).resolve(sha256.substring(2, 4)).resolve(sha256);
    }

    public static void writeHeader(OutputStream out, Codec codec, long originalSize) throws IOException {
        DataOutputStream d = new DataOutputStream(out);
        d.write(MAGIC);
        d.writeByte(VERSION);
        d.writeByte(codec.id());
        d.writeLong(originalSize);
        d.flush();
    }

    public static Header readHeader(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(in);
        byte[] m = new byte[4];
        d.readFully(m);
        if (!Arrays.equals(m, MAGIC)) throw new IOException("Not an object file");
        int v = d.readUnsignedByte();
        if (v != VERSION) throw new IOException("Unsupported object version " + v);
        Codec c = Codec.byId(d.readUnsignedByte());
        long size = d.readLong();
        if (size < 0) throw new IOException("Negative object size");
        return new Header(c, size);
    }

    /** Writes {@code data} with the given codec into {@code file} (no atomic rename here). */
    public static void write(Path file, byte[] data, Codec codec, int level) throws IOException {
        byte[] body = codec == Codec.RAW ? data : codec.compress(data, 0, data.length, level);
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            writeHeader(out, codec, data.length);
            out.write(body);
        }
    }

    /** Streams a file of {@code originalSize} bytes from {@code src} into an object file. */
    public static void writeStream(Path file, InputStream src, long originalSize, Codec codec, int level) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = new java.io.BufferedOutputStream(Files.newOutputStream(file), 1 << 16)) {
            writeHeader(out, codec, originalSize);
            OutputStream body = codec.wrapOut(out, level);
            src.transferTo(body);
            body.flush();
            if (body != out) body.close();
        }
    }

    public static byte[] read(Path file) throws IOException {
        Header h=header(file);
        if(h.originalSize()>(8L<<20))throw new IOException("Use a stream for large objects");
        try(InputStream in=openContent(file)) {
            byte[] data=in.readNBytes((int)h.originalSize());
            if(data.length!=h.originalSize() || in.read()!=-1)throw new IOException("Stored object length mismatch");return data;
        }
    }

    /** Opens a decoded content stream; caller closes it. */
    public static InputStream openContent(Path file) throws IOException {
        InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
        try {
            Header h = readHeader(in);
            return h.codec().wrapIn(in);
        } catch (IOException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    public static Header header(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return readHeader(in);
        }
    }
}
