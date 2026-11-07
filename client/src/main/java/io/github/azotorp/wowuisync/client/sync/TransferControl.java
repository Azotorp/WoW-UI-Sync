package io.github.azotorp.wowuisync.client.sync;

import io.github.azotorp.wowuisync.client.util.CancelledException;

/** Pause/cancel flags read by long operations. */
public class TransferControl {
    private volatile boolean paused;
    private volatile boolean cancelled;

    public boolean paused() { return paused; }
    public boolean cancelled() { return cancelled; }
    public void setPaused(boolean p) { paused = p; }
    public void cancel() { cancelled = true; }

    /** Blocks while paused; throws if cancelled. */
    public void checkpoint() throws CancelledException {
        while (paused && !cancelled) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancelledException();
            }
        }
        if (cancelled) throw new CancelledException();
    }
}
