package io.github.azotorp.wowuisync.client.ui;

import io.github.azotorp.wowuisync.common.crypto.SshPublicKeys;

import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.FlowLayout;
import java.util.Arrays;

/** "Public key line or password (twice)" input used by account dialogs. */
final class CredentialInput {
    final JRadioButton useKey = new JRadioButton("Public key");
    final JRadioButton usePw = new JRadioButton("Password");
    final JTextArea key = new JTextArea(3, 36);
    final JPasswordField pw1 = new JPasswordField(20);
    final JPasswordField pw2 = new JPasswordField(20);

    /** Validated result: exactly one of the two is non-null. */
    record Value(String publicKey, String password) {}

    CredentialInput(boolean keyDefault) {
        ButtonGroup g = new ButtonGroup();
        g.add(useKey);
        g.add(usePw);
        (keyDefault ? useKey : usePw).setSelected(true);
        key.setLineWrap(true);
        useKey.addActionListener(e -> update());
        usePw.addActionListener(e -> update());
        update();
    }

    void addTo(Forms f) {
        JPanel m = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        m.add(useKey);
        m.add(Box.createHorizontalStrut(12));
        m.add(usePw);
        f.add("Log in with:", m);
        f.add("Public key line:", new JScrollPane(key));
        f.add("Password:", pw1);
        f.add("Repeat password:", pw2);
    }

    private void update() {
        boolean k = useKey.isSelected();
        key.setEnabled(k);
        pw1.setEnabled(!k);
        pw2.setEnabled(!k);
    }

    /** Returns the value or throws with a user-facing message. */
    Value value() {
        if (useKey.isSelected()) {
            String line = key.getText().trim();
            SshPublicKeys.parse(line);
            return new Value(line, null);
        }
        if (pw1.getPassword().length < 8) throw new IllegalArgumentException("Passwords must be at least 8 characters.");
        if (!Arrays.equals(pw1.getPassword(), pw2.getPassword())) throw new IllegalArgumentException("The passwords do not match.");
        return new Value(null, new String(pw1.getPassword()));
    }
}
