package io.github.azotorp.wowuisync.client.ui;

import io.github.azotorp.wowuisync.client.data.ClientDb;
import io.github.azotorp.wowuisync.client.data.ConnectionRecord;
import io.github.azotorp.wowuisync.common.Defaults;
import io.github.azotorp.wowuisync.common.crypto.CertFingerprint;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import java.io.File;
import java.util.UUID;

/** New/Edit Connection (DESIGN.md §17.2). */
public final class ConnectionEditDialog extends JDialog {
    private final JTextField name = new JTextField(24);
    private final JTextField host = new JTextField(Defaults.CLIENT_HOST, 24);
    private final JSpinner port = new JSpinner(new SpinnerNumberModel(Defaults.PORT, 1, 65535, 1));
    private final JTextField username = new JTextField(24);
    private final JRadioButton usePassword = new JRadioButton("Password");
    private final JRadioButton useKey = new JRadioButton("Private key file");
    private final JPasswordField password = new JPasswordField(24);
    private final JCheckBox savePassword = new JCheckBox("Save in OS credential vault");
    private final JTextField keyPath = new JTextField(24);
    private final JButton browse = new JButton("Browse…");
    private ConnectionRecord result;

    private ConnectionEditDialog(Window owner, ConnectionRecord existing, ClientDb db) {
        super(owner, existing == null ? "New connection" : "Edit connection", ModalityType.APPLICATION_MODAL);
        ButtonGroup g = new ButtonGroup();
        g.add(usePassword);
        g.add(useKey);
        usePassword.setSelected(true);
        port.setEditor(new JSpinner.NumberEditor(port, "#"));
        if (existing != null) {
            name.setText(existing.name());
            host.setText(existing.host());
            port.setValue(existing.port());
            username.setText(existing.username());
            if (existing.usesKey()) useKey.setSelected(true);
            if (existing.password() != null) {
                password.setText(existing.password());
                savePassword.setSelected(true);
            }
            if (existing.keyPath() != null) keyPath.setText(existing.keyPath());
        }
        JPanel auth = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        auth.add(usePassword);
        auth.add(Box.createHorizontalStrut(12));
        auth.add(useKey);
        JPanel keyRow = new JPanel(new BorderLayout(6, 0));
        keyRow.add(keyPath, BorderLayout.CENTER);
        keyRow.add(browse, BorderLayout.EAST);
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(keyPath.getText().isBlank() ? new File(System.getProperty("user.home"), ".ssh")
                    : new File(keyPath.getText()).getParentFile());
            fc.setDialogTitle("Choose your OpenSSH private key (not the .pub file)");
            fc.setFileHidingEnabled(false);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) keyPath.setText(fc.getSelectedFile().getAbsolutePath());
        });

        Forms f = new Forms()
                .add("Name:", name)
                .add("Host:", host)
                .add("Port:", port)
                .add("Username:", username)
                .add("Log in with:", auth)
                .add("Password:", password)
                .add("", savePassword)
                .add("Key file:", keyRow);
        JLabel hint = new JLabel("<html><small>Keys: ssh-ed25519, ssh-rsa (2048+), ecdsa-sha2-nistp256 in OpenSSH format. "
                + "PuTTYgen users: Conversions &gt; Export OpenSSH key.<br>Leave the password empty to be asked each time.</small></html>");
        f.full(hint);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        if (existing != null) {
            JButton forget = new JButton("Forget certificate");
            forget.setToolTipText("Use after the server was reinstalled, once you have checked its new fingerprint.");
            forget.addActionListener(e -> {
                try {
                    String hp = ConnectionRecord.hostPort(host.getText(), (Integer) port.getValue());
                    String pinned = db.pinned(hp);
                    if (pinned == null) {
                        Dialogs.info(this, "No certificate is pinned for " + hp + ".");
                    } else if (Dialogs.confirmWarning(this, "Forget the pinned certificate for " + hp + "?\n\n"
                            + CertFingerprint.pretty(pinned) + "\n\nYou will be asked to confirm the fingerprint on the next connect.")) {
                        db.unpin(hp);
                    }
                } catch (Exception ex) {
                    Dialogs.error(this, ex);
                }
            });
            buttons.add(forget);
        }
        JButton ok = new JButton("Save");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> save(existing));
        cancel.addActionListener(e -> dispose());
        buttons.add(ok);
        buttons.add(cancel);
        getRootPane().setDefaultButton(ok);

        usePassword.addActionListener(e -> updateEnabled());
        useKey.addActionListener(e -> updateEnabled());
        updateEnabled();

        JPanel root = new JPanel(new BorderLayout());
        root.add(f.panel(), BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        root.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        setContentPane(root);
        pack();
        setLocationRelativeTo(owner);
    }

    private void updateEnabled() {
        boolean pw = usePassword.isSelected();
        password.setEnabled(pw);
        savePassword.setEnabled(pw);
        keyPath.setEnabled(!pw);
        browse.setEnabled(!pw);
    }

    private void save(ConnectionRecord existing) {
        String h = host.getText().trim();
        String u = username.getText().trim();
        if (h.isEmpty() || u.isEmpty()) {
            Dialogs.error(this, "Enter a host and a username.");
            return;
        }
        if (useKey.isSelected() && keyPath.getText().isBlank()) {
            Dialogs.error(this, "Choose your private key file.");
            return;
        }
        String n = name.getText().trim().isEmpty() ? u + "@" + h : name.getText().trim();
        String pw = usePassword.isSelected() && savePassword.isSelected() && password.getPassword().length > 0
                ? new String(password.getPassword()) : null;
        result = new ConnectionRecord(existing == null ? UUID.randomUUID().toString() : existing.id(), n, h, (Integer) port.getValue(), u,
                useKey.isSelected() ? ConnectionRecord.AUTH_KEY : ConnectionRecord.AUTH_PASSWORD, pw,
                useKey.isSelected() ? keyPath.getText().trim() : null);
        dispose();
    }

    /** Returns the edited record (not yet saved), or null on cancel. */
    public static ConnectionRecord edit(Window owner, ConnectionRecord existing, ClientDb db) {
        ConnectionEditDialog d = new ConnectionEditDialog(owner, existing, db);
        d.setVisible(true);
        return d.result;
    }
}
