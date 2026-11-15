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
    private final JPanel info = new JPanel(new GridLayout(0, 1, 0, 2));
    private final JLabel currentFile = new JLabel(" ");
    private final JButton pause = new JButton("Pause");
    private final JButton cancel = new JButton("Cancel");
    private volatile Control control;
    private volatile long started, lastCount;
    private volatile long operationStarted, lastOperationUpdate;
    private volatile String fileOperationPhase="";

    /** Pause/cancel flags read by the transfer. */
    public static final class Control extends io.github.azotorp.wowuisync.client.sync.TransferControl {}

    public ProgressPanel() {
        super(new BorderLayout(0, 6));
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder("Progress"),
                BorderFactory.createEmptyBorder(6, 8, 8, 8)));
        bar.setStringPainted(true);title.setName("phaseTitle");files.setName("fileStatistics");
        info.add(files);
        info.add(bytes);
        info.add(speed);
        info.add(currentFile);
        currentFile.setName("currentFile");
        bytes.setName("dataStatistics");
        speed.setName("rateStatistics");
        currentFile.setMinimumSize(new java.awt.Dimension(0, currentFile.getPreferredSize().height));
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
            setNote(why);
        });
    }

    /** Starts showing a new operation. */
    public Control begin(String what, boolean pausable) {
        started=System.nanoTime();lastCount=0;fileOperationPhase="";
        Control c = new Control();
        control = c;
        SwingUtilities.invokeLater(() -> {
            title.setText(what);
            bar.setIndeterminate(true);
            bar.setValue(0);
            files.setText(" ");
            bytes.setText(" ");
            speed.setText(" ");
            setNote("");
            currentFile.setText(" ");currentFile.setToolTipText(null);
            pause.setText("Pause");
            pause.setEnabled(pausable);
            cancel.setEnabled(true);
            setVisible(true);
        });
        return c;
    }

    public void phase(String text) {
        fileOperationPhase="";lastCount=0;
        SwingUtilities.invokeLater(() -> {
            title.setText(text);
            files.setText(" ");bytes.setText(" ");speed.setText(" ");setNote("");
            currentFile.setText(" ");currentFile.setToolTipText(null);
            bar.setValue(0);bar.setIndeterminate(true);
        });
    }

    public void fileOperation(String phase, String path, long done, long total, long completedBytes, long totalBytes) {
        long tick=System.nanoTime();
        boolean changed=!phase.equals(fileOperationPhase);
        if(changed){fileOperationPhase=phase;operationStarted=tick;lastOperationUpdate=0;}
        if(!changed && done!=total && tick-lastOperationUpdate<100_000_000L)return;
        lastOperationUpdate=tick;
        double elapsed=(tick-operationStarted)/1e9;
        String normalized=path==null?"":path.replace('\\','/');
        SwingUtilities.invokeLater(() -> {
            title.setText(phase+"…");setNote("");
            files.setText(done<0?"Files: native folder copy in progress":total>=0?"Files: "+done+" of "+total:"Files: "+done);
            bytes.setText(completedBytes<0?"Data: native folder copy in progress":totalBytes>=0?"Data: "+Sizes.human(completedBytes)+" of "+Sizes.human(totalBytes):"Data: "+Sizes.human(completedBytes));
            double rate=elapsed>0.001&&completedBytes>=0?completedBytes/elapsed:0;
            long eta=rate>0&&totalBytes>=completedBytes?(long)((totalBytes-completedBytes)/rate):-1;
            speed.setText(completedBytes<0?"Elapsed "+Sizes.duration((long)elapsed):"Speed: "+Sizes.human((long)rate)+"/s · elapsed "+Sizes.duration((long)elapsed)+(eta>=0?" · ETA "+Sizes.duration(eta):""));
            currentFile.setText(normalized.isEmpty()?" ":normalized);currentFile.setToolTipText(normalized.isEmpty()?null:normalized);
            bar.setIndeterminate(total<=0);if(total>0)bar.setValue((int)Math.min(1000,done*1000/total));
        });
    }

    public void note(String text) {
        SwingUtilities.invokeLater(() -> setNote(text));
    }

    private void setNote(String text) {
        boolean empty=text==null || text.isEmpty();
        note.setText(empty ? " " : text);
        if(empty && note.getParent()==info)info.remove(note);
        else if(!empty && note.getParent()!=info)info.add(note,Math.min(3,info.getComponentCount()));
        info.revalidate();info.repaint();
    }

    public void currentFile(String path) {
        SwingUtilities.invokeLater(() -> {
            currentFile.setText(path == null || path.isEmpty() ? " " : path);
            currentFile.setToolTipText(path == null || path.isEmpty() ? null : path);
        });
    }

    public void scanMetrics(int cacheHits, int hashed, long bytesRead, double elapsedSeconds, double etaSeconds, int workers) {
        SwingUtilities.invokeLater(() -> {
            bytes.setText("Read: " + Sizes.human(bytesRead) + " · cache hits: " + cacheHits + " · hashed: " + hashed + " · workers: " + workers);
            double rate = bytesRead / Math.max(0.001, elapsedSeconds);
            speed.setText("Read speed: " + Sizes.human((long)rate) + "/s · elapsed "
                    + Sizes.duration((long)elapsedSeconds) + (etaSeconds >= 0 ? " · ETA " + Sizes.duration((long)etaSeconds) : ""));
        });
    }

    public void count(String label, long done, long total) {
        long tick=System.nanoTime();
        if(done!=total && tick-lastCount<100_000_000L)return;
        lastCount=tick;
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
            title.setText(text + " · elapsed " + Sizes.duration((System.nanoTime()-started)/1_000_000_000L));
            speed.setText(" ");
            currentFile.setText(" ");currentFile.setToolTipText(null);
            bar.setIndeterminate(false);
            bar.setValue(1000);
            pause.setEnabled(false);
            cancel.setEnabled(false);
        });
    }

    public void idle() {
        control = null;
        title.setText("No transfer running.");
        files.setText(" ");
        bytes.setText(" ");
        speed.setText(" ");
        setNote("");currentFile.setText(" ");currentFile.setToolTipText(null);
        bar.setIndeterminate(false);
        bar.setValue(0);
        pause.setEnabled(false);
        cancel.setEnabled(false);
    }
}
