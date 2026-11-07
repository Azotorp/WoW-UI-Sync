package io.github.azotorp.wowuisync.common.protocol;

/** Error codes used in {@code ERROR} messages. */
public final class ErrorCodes {
    public static final String BAD_REQUEST = "bad_request";
    public static final String NOT_LOGGED_IN = "not_logged_in";
    public static final String FORBIDDEN = "forbidden";
    public static final String NOT_FOUND = "not_found";
    public static final String QUOTA = "quota";
    public static final String CONFLICT = "conflict";
    public static final String INTEGRITY = "integrity";
    public static final String BUSY = "busy";
    public static final String INVALID = "invalid";
    public static final String IP_BLOCKED = "ip_blocked";
    public static final String UNAVAILABLE = "unavailable";
    public static final String INTERNAL = "internal";
    public static final String SHUTTING_DOWN = "shutting_down";

    private ErrorCodes() {}
}
