package io.github.azotorp.wowuisync.client.util;

/** An error with a message meant for the user. */
public class UserException extends Exception {
    public UserException(String message) {
        super(message);
    }

    public UserException(String message, Throwable cause) {
        super(message, cause);
    }
}
