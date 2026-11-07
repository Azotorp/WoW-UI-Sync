package io.github.azotorp.wowuisync.client.sync;

import java.util.concurrent.atomic.AtomicLong;

/** Counters for progress display. */
public final class TransferStats {
    public final long filesTotal;
    public final long bytesTotal;
    public final AtomicLong filesDone = new AtomicLong();
    public final AtomicLong bytesDone = new AtomicLong();
    public final AtomicLong wireBytes = new AtomicLong();
    private final long start = System.nanoTime();

    public TransferStats(long filesTotal, long bytesTotal) {
        this.filesTotal = filesTotal;
        this.bytesTotal = bytesTotal;
    }

    public double bytesPerSecond() {
        double secs = (System.nanoTime() - start) / 1e9;
        return secs < 0.5 ? 0 : bytesDone.get() / secs;
    }
}
