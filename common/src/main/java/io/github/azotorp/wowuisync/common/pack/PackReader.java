package io.github.azotorp.wowuisync.common.pack;

import io.github.azotorp.wowuisync.common.codec.Codec;
import io.github.azotorp.wowuisync.common.hash.Hashing;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Parses packs written by {@link PackWriter}. */
public final class PackReader {
    public record Pack(PackKind kind, Codec codec, List<PackEntry> entries) {}

    private PackReader() {}

    /**
     * Reads a pack. If {@code verify} is set, every entry's SHA-256 is checked and a mismatch throws.
     */
    public static Pack read(byte[] data, int off, int len, boolean verify) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data, off, len));
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!Arrays.equals(magic, PackWriter.MAGIC)) throw new IOException("Not a pack");
        int version = in.readUnsignedByte();
        if (version != PackWriter.VERSION) throw new IOException("Unsupported pack version " + version);
        PackKind kind = PackKind.byId(in.readUnsignedByte());
        Codec codec = Codec.byId(in.readUnsignedByte());
        int count = in.readInt();
        if (count < 0 || count > 100_000) throw new IOException("Bad pack count");
        String[] hashes = new String[count];
        long[] sizes = new long[count];
        long total = 0;
        for (int i = 0; i < count; i++) {
            byte[] h = new byte[32];
            in.readFully(h);
            hashes[i] = Hashing.hex(h);
            sizes[i] = in.readLong();
            if (sizes[i] < 0 || sizes[i] > (8L << 20)) throw new IOException("Bad entry size");
            total += sizes[i];
            if (total > (16L << 20)) throw new IOException("Pack too large");
        }
        InputStream body = codec.wrapIn(in);
        List<PackEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] b = body.readNBytes((int) sizes[i]);
            if (b.length != sizes[i]) throw new IOException("Truncated pack");
            if (verify && !Hashing.sha256Hex(b).equals(hashes[i])) {
                throw new IOException("Checksum mismatch for " + hashes[i]);
            }
            entries.add(new PackEntry(hashes[i], b));
        }
        if (body.read() != -1) throw new IOException("Unexpected extra pack content");
        return new Pack(kind, codec, entries);
    }
}
