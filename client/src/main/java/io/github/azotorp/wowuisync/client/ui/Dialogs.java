package io.github.azotorp.wowuisync.client.ui;

import io.github.azotorp.wowuisync.client.util.UserException;
import io.github.azotorp.wowuisync.common.protocol.ProtocolException;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Small dialog helpers. */
public final class Dialogs {
    private Dialogs() {}

    public static String message(Throwable e) {
        Throwable t = e;
        while (t instanceof InvocationTargetException || (t != null && t.getClass() == RuntimeException.class && t.getCause() != null)) {
            t = t.getCause();
        }
        if (t instanceof UserException || t instanceof ProtocolException) return t.getMessage();
        if (t instanceof java.nio.file.AccessDeniedException a) return "Access denied: " + a.getFile();
        if (t instanceof java.nio.file.NoSuchFileException n) return "File not found: " + n.getFile();
        if (t instanceof java.io.IOException) return "Connection or file error: " + t.getMessage();
        return t == null ? "Unknown error" : t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    public static void error(Component parent, Throwable e) {
        error(parent, message(e));
    }

    public static void error(Component parent, String msg) {
        JOptionPane.showMessageDialog(parent, wrap(msg), "WoW UI Sync", JOptionPane.ERROR_MESSAGE);
    }

    public static void info(Component parent, String msg) {
        JOptionPane.showMessageDialog(parent, wrap(msg), "WoW UI Sync", JOptionPane.INFORMATION_MESSAGE);
    }

    public static void warn(Component parent, String msg) {
        JOptionPane.showMessageDialog(parent, wrap(msg), "WoW UI Sync", JOptionPane.WARNING_MESSAGE);
    }

    public static boolean confirm(Component parent, String msg) {
        return JOptionPane.showConfirmDialog(parent, wrap(msg), "WoW UI Sync", JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION;
    }

    public static boolean confirmWarning(Component parent, String msg) {
        return JOptionPane.showConfirmDialog(parent, wrap(msg), "WoW UI Sync", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /** Multi-line message as a read-only text area when long. */
    static Object wrap(String msg) {
        if (msg == null) return "";
        if (msg.length() < 300 && msg.split("\n").length < 12) return msg;
        JTextArea a = new JTextArea(msg);
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JScrollPane sp = new JScrollPane(a);
        sp.setPreferredSize(new Dimension(520, 260));
        return sp;
    }

    public static String askText(Component parent, String label, String initial) {
        Object r = JOptionPane.showInputDialog(parent, label, "WoW UI Sync", JOptionPane.QUESTION_MESSAGE, null, null, initial);
        return r == null ? null : r.toString();
    }

    public static char[] askSecret(Component parent, String label) {
        JPasswordField pf = new JPasswordField(24);
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.add(new JLabel(label), BorderLayout.NORTH);
        p.add(pf, BorderLayout.CENTER);
        pf.addAncestorListener(new FocusOnShow());
        int r = JOptionPane.showConfirmDialog(parent, p, "WoW UI Sync", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        return r == JOptionPane.OK_OPTION ? pf.getPassword() : null;
    }

    /** Asks for a new password twice. Returns null on cancel. */
    public static char[] askNewPassword(Component parent, String title) {
        while (true) {
            JPasswordField a = new JPasswordField(24);
            JPasswordField b = new JPasswordField(24);
            JPanel p = new JPanel(new GridLayout(0, 1, 0, 4));
            p.add(new JLabel(title));
            p.add(new JLabel("New password (at least 8 characters):"));
            p.add(a);
            p.add(new JLabel("Repeat the password:"));
            p.add(b);
            a.addAncestorListener(new FocusOnShow());
            int r = JOptionPane.showConfirmDialog(parent, p, "WoW UI Sync", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return null;
            if (a.getPassword().length < 8) {
                error(parent, "Passwords must be at least 8 characters.");
                continue;
            }
            if (!java.util.Arrays.equals(a.getPassword(), b.getPassword())) {
                error(parent, "The passwords do not match.");
                continue;
            }
            return a.getPassword();
        }
    }

    /** Requires typing {@code expected} exactly to confirm. */
    public static boolean confirmTyped(Component parent, String message, String expected) {
        JTextField f = new JTextField(20);
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.add(new JLabel("<html><body style='width:380px'>" + escape(message) + "<br><br>Type <b>" + escape(expected)
                + "</b> to confirm:</body></html>"), BorderLayout.NORTH);
        p.add(f, BorderLayout.CENTER);
        f.addAncestorListener(new FocusOnShow());
        int r = JOptionPane.showConfirmDialog(parent, p, "WoW UI Sync", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return false;
        if (!f.getText().trim().equals(expected)) {
            error(parent, "The text did not match. Nothing was changed.");
            return false;
        }
        return true;
    }

    public static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }

    /** Runs {@code s} on the EDT and waits for the result (for callbacks from worker threads). */
    public static <T> T onEdt(Supplier<T> s) {
        if (SwingUtilities.isEventDispatchThread()) return s.get();
        AtomicReference<T> r = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> r.set(s.get()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
        return r.get();
    }

    static final class FocusOnShow implements javax.swing.event.AncestorListener {
        @Override
        public void ancestorAdded(javax.swing.event.AncestorEvent e) {
            SwingUtilities.invokeLater(() -> e.getComponent().requestFocusInWindow());
        }

        @Override
        public void ancestorRemoved(javax.swing.event.AncestorEvent e) {}

        @Override
        public void ancestorMoved(javax.swing.event.AncestorEvent e) {}
    }
}
