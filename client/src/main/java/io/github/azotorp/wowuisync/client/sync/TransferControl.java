package io.github.azotorp.wowuisync.client.sync;

import io.github.azotorp.wowuisync.client.util.CancelledException;

/** Pause/cancel flags read by long operations. */
public class TransferControl {
    private volatile boolean paused;
    private volatile boolean cancelled;
    private Runnable cancellation;

    public boolean paused() { return paused; }
    public boolean cancelled() { return cancelled; }
    public void setPaused(boolean p) { paused = p; }
    public void cancel() {
        Runnable action;
        synchronized(this) { cancelled=true;action=cancellation;cancellation=null; }
        if(action!=null)action.run();
    }
    public void onCancel(Runnable action) {
        boolean run;
        synchronized(this) { run=cancelled;cancellation=run?null:action; }
        if(run && action!=null)action.run();
    }

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
