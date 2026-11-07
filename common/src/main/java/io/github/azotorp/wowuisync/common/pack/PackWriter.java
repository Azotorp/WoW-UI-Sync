package io.github.azotorp.wowuisync.common.pack;

import io.github.azotorp.wowuisync.common.codec.Codec;
import io.github.azotorp.wowuisync.common.hash.Hashing;
import io.github.azotorp.wowuisync.common.path.SyncPaths;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds a pack:
 * <pre>
 * "WUSP" | u8 version | u8 kind | u8 codec | u32 count
 * count × (32-byte sha256, u64 size)
 * body: concatenated file bytes (RAW) or one compressed stream of them (COMPRESSED)
 * </pre>
 * Entries are sorted by extension, then path, which groups similar content for better compression.
 */
public final class PackWriter {
    public static final byte[] MAGIC = {'W', 'U', 'S', 'P'};
    public static final int VERSION = 1;

    private record Item(String path, String sha256, byte[] data) {}

    private final PackKind kind;
    private final Codec codec;
    private final int level;
    private final List<Item> items = new ArrayList<>();
    private long bytes;

    public PackWriter(PackKind kind, Codec codec, int level) {
        this.kind = kind;
        this.codec = kind == PackKind.RAW ? Codec.RAW : codec;
        this.level = level;
    }

    public PackKind kind() {
        return kind;
    }

    public void add(String path, String sha256, byte[] data) {
        items.add(new Item(path == null ? "" : path, sha256, data));
        bytes += data.length;
    }

    public int count() {
        return items.size();
    }

    public long uncompressedBytes() {
        return bytes;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public byte[] build() {
        items.sort(Comparator.comparing((Item i) -> SyncPaths.extension(i.path()))
                .thenComparing(i -> SyncPaths.key(i.path())));
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(Integer.MAX_VALUE - 64, bytes + 64 + items.size() * 40L));
            DataOutputStream out = new DataOutputStream(bos);
            out.write(MAGIC);
            out.writeByte(VERSION);
            out.writeByte(kind.id());
            out.writeByte(codec.id());
            out.writeInt(items.size());
            for (Item i : items) {
                out.write(Hashing.unhex(i.sha256()));
                out.writeLong(i.data().length);
            }
            out.flush();
            OutputStream body = codec.wrapOut(nonClosing(bos), level);
            for (Item i : items) body.write(i.data());
            body.close();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static OutputStream nonClosing(OutputStream o) {
        return new java.io.FilterOutputStream(o) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                flush();
            }
        };
    }
}
