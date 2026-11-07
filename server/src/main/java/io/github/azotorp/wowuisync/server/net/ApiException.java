package io.github.azotorp.wowuisync.server.net;

/** A request failure reported to the client as an {@code ERROR} message. */
public class ApiException extends Exception {
    private final String code;

    public ApiException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
