package io.github.azotorp.wowuisync.common.protocol;

import io.github.azotorp.wowuisync.common.Defaults;
import io.github.azotorp.wowuisync.common.codec.Codec;
import io.github.azotorp.wowuisync.common.json.Json;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;

/** Frame reader/writer over a stream pair. Writes are serialized; reads are expected from one thread. */
public final class Wire implements Closeable {
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Closeable underlying;
    private final Object writeLock = new Object();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private volatile Codec codec = Codec.RAW;

    public Wire(InputStream in, OutputStream out, Closeable underlying) {
        this.in = new DataInputStream(new BufferedInputStream(in, 1 << 16));
        this.out = new DataOutputStream(new BufferedOutputStream(out, 1 << 16));
        this.underlying = underlying;
    }

    /** Codec for control payloads (RAW until negotiated by HELLO). */
    public void setCodec(Codec c) {
        this.codec = c;
    }

    public Codec codec() {
        return codec;
    }

    public int newRequestId() {
        return nextId.getAndIncrement();
    }

    public void send(int requestId, Object message) throws IOException {
        MessageType t = MessageType.forClass(message.getClass());
        if (t.binary()) {
            sendBinary(requestId, message, new byte[0], 0, 0);
            return;
        }
        byte[] json = Json.toBytes(message);
        // Small payloads are not worth compressing.
        Codec c = json.length < 512 ? Codec.RAW : codec;
        byte[] payload = c.encodeTagged(json);
        writeFrame(t, requestId, payload, null, 0, 0);
    }

    /** Sends a binary frame: JSON header + body bytes. */
    public void sendBinary(int requestId, Object header, byte[] body, int off, int len) throws IOException {
        MessageType t = MessageType.forClass(header.getClass());
        if (!t.binary()) throw new IllegalArgumentException(t + " is not binary");
        byte[] h = Json.toBytes(header);
        byte[] prefix = new byte[4 + h.length];
        prefix[0] = (byte) (h.length >>> 24);
        prefix[1] = (byte) (h.length >>> 16);
        prefix[2] = (byte) (h.length >>> 8);
        prefix[3] = (byte) h.length;
        System.arraycopy(h, 0, prefix, 4, h.length);
        writeFrame(t, requestId, prefix, body, off, len);
    }

    private void writeFrame(MessageType t, int requestId, byte[] a, byte[] b, int off, int len) throws IOException {
        long total = (long) a.length + len;
        if (total > Defaults.MAX_FRAME_PAYLOAD) throw new IOException("Frame too large: " + total);
        synchronized (writeLock) {
            out.writeByte(t.code());
            out.writeInt(requestId);
            out.writeInt((int) total);
            out.write(a);
            if (b != null && len > 0) out.write(b, off, len);
            out.flush();
        }
    }

    public void sendError(int requestId, String code, String message) throws IOException {
        send(requestId, new Msg.Error(code, message));
    }

    /** Blocks for the next frame. Throws {@link EOFException} on a clean close. */
    public Frame read() throws IOException {
        int type = in.read();
        if (type < 0) throw new EOFException("Connection closed");
        int id = in.readInt();
        int len = in.readInt();
        if (len < 0 || len > Defaults.MAX_FRAME_PAYLOAD) throw new IOException("Bad frame length " + len);
        byte[] payload = new byte[len];
        in.readFully(payload);
        return new Frame(MessageType.byCode(type), id, payload);
    }

    /** Sends a request and reads the next frame (for strictly request/response connections). */
    public Frame call(Object message) throws IOException {
        int id = newRequestId();
        send(id, message);
        return read();
    }

    public <T> T call(Object message, Class<T> reply) throws IOException {
        return call(message).message(reply);
    }

    @Override
    public void close() {
        try {
            underlying.close();
        } catch (IOException ignored) {
            // ignore
        }
    }
}
