package io.github.azotorp.wowuisync.common.protocol;

import java.io.IOException;

/** An {@code ERROR} reply from the peer. */
public class ProtocolException extends IOException {
    private final String code;

    public ProtocolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
