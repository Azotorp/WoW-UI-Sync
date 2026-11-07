package io.github.azotorp.wowuisync.client.ui;

import io.github.azotorp.wowuisync.client.util.CancelledException;
import io.github.azotorp.wowuisync.client.util.ClientLog;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Runs work off the Swing thread and reports back on it. */
public final class Bg {
    private static final ExecutorService EXEC = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "worker");
        t.setDaemon(true);
        return t;
    });

    private Bg() {}

    /** Runs {@code task}; on success calls {@code ok} on the EDT; errors are shown in a dialog. */
    public static <T> void run(Component parent, Callable<T> task, Consumer<T> ok) {
        run(task, ok, e -> {
            if (!(e instanceof CancelledException)) Dialogs.error(parent, e);
        });
    }

    public static <T> void run(Callable<T> task, Consumer<T> ok, Consumer<Throwable> err) {
        EXEC.submit(() -> {
            try {
                T v = task.call();
                SwingUtilities.invokeLater(() -> {
                    if (ok != null) ok.accept(v);
                });
            } catch (Throwable e) {
                if (!(e instanceof CancelledException)) ClientLog.LOG.log(Level.INFO, "Background task failed: " + e.getMessage(), e);
                SwingUtilities.invokeLater(() -> {
                    if (err != null) err.accept(e);
                });
            }
        });
    }
}
