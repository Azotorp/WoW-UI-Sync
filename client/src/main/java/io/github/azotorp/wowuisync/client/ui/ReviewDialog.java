package io.github.azotorp.wowuisync.client.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.util.List;

/**
 * Review dialog for push and pull (DESIGN.md §17.3): New, Edited, Removed files as plain lists. No diffs.
 * When {@code typedConfirm} is set, Yes stays disabled until that username is typed.
 */
public final class ReviewDialog extends JDialog {
    private boolean accepted;

    private ReviewDialog(Window owner, String title, String headline, String[] labels, List<String> added, List<String> edited,
                         List<String> removed, String footer, String typedConfirm, boolean infoOnly) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        JPanel lists = new JPanel(new GridLayout(3, 1, 0, 8));
        lists.add(section(labels[0] + " (" + added.size() + ")", added));
        lists.add(section(labels[1] + " (" + edited.size() + ")", edited));
        lists.add(section(labels[2] + " (" + removed.size() + ")", removed));
        JButton yes = new JButton(infoOnly ? "Close" : "Yes");
        JButton cancel = new JButton("Cancel");
        yes.addActionListener(e -> {
            accepted = true;
            dispose();
        });
        cancel.addActionListener(e -> dispose());
        JPanel south = new JPanel(new BorderLayout(0, 6));
        if (footer != null) south.add(new JLabel("<html><body style='width:560px'>" + Dialogs.escape(footer) + "</body></html>"), BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        if (typedConfirm != null) {
            JTextField t = new JTextField(16);
            yes.setEnabled(false);
            t.getDocument().addDocumentListener(new DocumentListener() {
                void check() {
                    yes.setEnabled(t.getText().trim().equals(typedConfirm));
                }

                @Override public void insertUpdate(DocumentEvent e) { check(); }
                @Override public void removeUpdate(DocumentEvent e) { check(); }
                @Override public void changedUpdate(DocumentEvent e) { check(); }
            });
            buttons.add(new JLabel("Type " + typedConfirm + " to confirm:"));
            buttons.add(t);
        }
        buttons.add(yes);
        if (!infoOnly) buttons.add(cancel);
        south.add(buttons, BorderLayout.SOUTH);
        JLabel head = new JLabel("<html><body style='width:560px'><b>" + Dialogs.escape(headline) + "</b></body></html>");
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(head, BorderLayout.NORTH);
        root.add(lists, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(typedConfirm == null ? yes : null);
        pack();
        setLocationRelativeTo(owner);
    }

    private static JPanel section(String title, List<String> items) {
        JList<String> l = new JList<>(items.toArray(new String[0]));
        l.setVisibleRowCount(6);
        JScrollPane sp = new JScrollPane(l);
        sp.setPreferredSize(new Dimension(640, 130));
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    public static boolean show(Window owner, String title, String headline, List<String> added, List<String> edited, List<String> removed,
                               String footer, String typedConfirm) {
        ReviewDialog d = new ReviewDialog(owner, title, headline, new String[] {"New files", "Edited files", "Removed files"},
                added, edited, removed, footer, typedConfirm, false);
        d.setVisible(true);
        return d.accepted;
    }

    /** Read-only lists with custom section labels. */
    public static void info(Window owner, String title, String headline, String[] labels, List<String> a, List<String> b, List<String> c) {
        new ReviewDialog(owner, title, headline, labels, a, b, c, null, null, true).setVisible(true);
    }
}
