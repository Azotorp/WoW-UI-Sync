package io.github.azotorp.wowuisync.client.ui;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Modal "working…" dialog for local operations (backups, restore, deletes, scans). */
public final class BusyDialog extends JDialog {
    private final JLabel label = new JLabel(" ");
    private final JProgressBar bar = new JProgressBar(0, 1000);

    private BusyDialog(Window owner, String title) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        bar.setIndeterminate(true);
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        p.add(label, BorderLayout.NORTH);
        p.add(bar, BorderLayout.CENTER);
        p.setPreferredSize(new Dimension(420, 90));
        setContentPane(p);
        pack();
        setLocationRelativeTo(owner);
    }

    /** Progress callback usable from any thread. */
    public void update(String text, long done, long total) {
        SwingUtilities.invokeLater(() -> {
            label.setText(total > 0 ? text + ": " + done + " of " + total : done > 0 ? text + ": " + done : text);
            bar.setIndeterminate(total <= 0);
            if (total > 0) bar.setValue((int) Math.min(1000, done * 1000 / total));
        });
    }

    /** Runs {@code task} with the dialog shown; calls {@code ok} on success, shows errors otherwise. */
    public static <T> void run(Window owner, String title, Function<BusyDialog, Callable<T>> task, Consumer<T> ok) {
        BusyDialog d = new BusyDialog(owner, title);
        d.label.setText(title + "…");
        Bg.run(task.apply(d), v -> {
            d.dispose();
            if (ok != null) ok.accept(v);
        }, e -> {
            d.dispose();
            if (!(e instanceof io.github.azotorp.wowuisync.client.util.CancelledException)) Dialogs.error(owner, e);
        });
        d.setVisible(true);
    }

    /** Functional interface allowing the task to reference the dialog for progress. */
    public interface Function<A, R> {
        R apply(A a);
    }
}
