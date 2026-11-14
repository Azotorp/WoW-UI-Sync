package io.github.azotorp.wowuisync.common.protocol;

import io.github.azotorp.wowuisync.common.codec.Codec;
import io.github.azotorp.wowuisync.common.json.Json;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A frame: {@code [u8 type][u32 requestId][u32 payloadLength][payload]}.
 * Control payloads: one codec byte + compressed JSON. Binary payloads: {@code [u32 headerLen][header JSON][bytes]}.
 */
public record Frame(MessageType type, int requestId, byte[] payload) {

    /** Decodes the JSON message of a control frame, or the header of a binary frame. */
    public <T> T message(Class<T> cls) throws IOException {
        if (!cls.isAssignableFrom(type.type())) {
            if (type == MessageType.ERROR) {
                Msg.Error err = (Msg.Error) decode();
                throw new ProtocolException(err.code(), err.message());
            }
            throw new IOException("Expected " + cls.getSimpleName() + " but got " + type);
        }
        return cls.cast(decode());
    }

    public Object decode() throws IOException {
        try {
            if (type.binary()) {
                ByteBuffer b = ByteBuffer.wrap(payload);
                int hl = b.getInt();
                if (hl < 0 || hl > 65536 || hl > payload.length - 4) throw new IOException("Bad binary header");
                byte[] h = new byte[hl];
                b.get(h);
                return Json.fromBytes(h, type.type());
            }
            return Json.fromBytes(Codec.decodeTagged(payload), type.type());
        } catch (RuntimeException e) {
            throw new IOException("Malformed " + type + " message", e);
        }
    }

    /** Offset of the binary body in {@link #payload()} for binary frames. */
    public int bodyOffset() {
        int hl = ByteBuffer.wrap(payload, 0, 4).getInt();
        return 4 + hl;
    }

    public int bodyLength() {
        return payload.length - bodyOffset();
    }

    public boolean isError() {
        return type == MessageType.ERROR;
    }
}
