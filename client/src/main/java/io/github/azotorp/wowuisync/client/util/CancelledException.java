package io.github.azotorp.wowuisync.client.util;

/** The user cancelled. */
public class CancelledException extends Exception {
    public CancelledException() {
        super("Cancelled");
    }
}
