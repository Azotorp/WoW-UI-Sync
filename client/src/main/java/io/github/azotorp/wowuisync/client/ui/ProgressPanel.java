package io.github.azotorp.wowuisync.client.ui;

import io.github.azotorp.wowuisync.common.util.Sizes;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;

/** Transfer progress (DESIGN.md §11.5): files and bytes, speed, ETA, original vs transferred, Pause/Resume, Cancel. */
public final class ProgressPanel extends JPanel {
    private final JLabel title = new JLabel(" ");
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final JLabel files = new JLabel(" ");
    private final JLabel bytes = new JLabel(" ");
    private final JLabel speed = new JLabel(" ");
    private final JLabel note = new JLabel(" ");
    private final JButton pause = new JButton("Pause");
    private final JButton cancel = new JButton("Cancel");
    private volatile Control control;

    /** Pause/cancel flags read by the transfer. */
    public static final class Control extends io.github.azotorp.wowuisync.client.sync.TransferControl {}

    public ProgressPanel() {
        super(new BorderLayout(0, 6));
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder("Progress"),
                BorderFactory.createEmptyBorder(6, 8, 8, 8)));
        bar.setStringPainted(true);
        JPanel info = new JPanel(new GridLayout(0, 1, 0, 2));
        info.add(files);
        info.add(bytes);
        info.add(speed);
        info.add(note);
        JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        b.add(pause);
        b.add(cancel);
        JPanel head = new JPanel(new BorderLayout(0, 4));
        head.add(title, BorderLayout.NORTH);
        head.add(bar, BorderLayout.CENTER);
        add(head, BorderLayout.NORTH);
        add(info, BorderLayout.CENTER);
        add(b, BorderLayout.SOUTH);
        pause.addActionListener(e -> {
            Control c = control;
            if (c == null) return;
            c.setPaused(!c.paused());
            pause.setText(c.paused() ? "Resume" : "Pause");
        });
        cancel.addActionListener(e -> {
            Control c = control;
            if (c != null) c.cancel();
        });
        idle();
    }

    /** Pauses from the transfer itself (e.g. connection lost), showing {@code why}; the user presses Resume. */
    public void pauseFromEngine(String why) {
        Control c = control;
        if (c == null) return;
        c.setPaused(true);
        SwingUtilities.invokeLater(() -> {
            pause.setText("Resume");
            pause.setEnabled(true);
            note.setText(why);
        });
    }

    /** Starts showing a new operation. */
    public Control begin(String what, boolean pausable) {
        Control c = new Control();
        control = c;
        SwingUtilities.invokeLater(() -> {
            title.setText(what);
            bar.setIndeterminate(true);
            bar.setValue(0);
            files.setText(" ");
            bytes.setText(" ");
            speed.setText(" ");
            note.setText(" ");
            pause.setText("Pause");
            pause.setEnabled(pausable);
            cancel.setEnabled(true);
            setVisible(true);
        });
        return c;
    }

    public void phase(String text) {
        SwingUtilities.invokeLater(() -> {
            title.setText(text);
            bar.setIndeterminate(true);
        });
    }

    public void note(String text) {
        SwingUtilities.invokeLater(() -> note.setText(text == null || text.isEmpty() ? " " : text));
    }

    public void count(String label, long done, long total) {
        SwingUtilities.invokeLater(() -> {
            bar.setIndeterminate(total <= 0);
            if (total > 0) bar.setValue((int) Math.min(1000, done * 1000 / total));
            files.setText(label + ": " + done + " of " + total);
        });
    }

    /** Transfer statistics. {@code wireBytes} = bytes actually sent or received. */
    public void transfer(long filesDone, long filesTotal, long bytesDone, long bytesTotal, long wireBytes, double bytesPerSec) {
        SwingUtilities.invokeLater(() -> {
            bar.setIndeterminate(bytesTotal <= 0);
            if (bytesTotal > 0) bar.setValue((int) Math.min(1000, bytesDone * 1000 / Math.max(1, bytesTotal)));
            files.setText("Files: " + filesDone + " of " + filesTotal);
            bytes.setText("Data: " + Sizes.human(bytesDone) + " of " + Sizes.human(bytesTotal)
                    + " (transferred " + Sizes.human(wireBytes) + " after compression)");
            long eta = bytesPerSec > 1 ? (long) ((bytesTotal - bytesDone) / bytesPerSec) : -1;
            speed.setText("Speed: " + Sizes.human((long) bytesPerSec) + "/s · ETA " + Sizes.duration(eta));
        });
    }

    public void done(String text) {
        control = null;
        SwingUtilities.invokeLater(() -> {
            title.setText(text);
            bar.setIndeterminate(false);
            bar.setValue(1000);
            pause.setEnabled(false);
            cancel.setEnabled(false);
        });
    }

    public void idle() {
        control = null;
        title.setText("No transfer running.");
        bar.setIndeterminate(false);
        bar.setValue(0);
        pause.setEnabled(false);
        cancel.setEnabled(false);
    }
}
