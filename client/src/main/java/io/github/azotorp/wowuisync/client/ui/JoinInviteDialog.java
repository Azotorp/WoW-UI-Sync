package io.github.azotorp.wowuisync.client.ui;

import io.github.azotorp.wowuisync.client.ClientApp;
import io.github.azotorp.wowuisync.client.crypto.PrivateKeys;
import io.github.azotorp.wowuisync.client.data.ConnectionRecord;
import io.github.azotorp.wowuisync.client.net.ServerConnection;
import io.github.azotorp.wowuisync.client.util.CancelledException;
import io.github.azotorp.wowuisync.common.protocol.InviteCode;
import io.github.azotorp.wowuisync.common.protocol.Msg;
import io.github.azotorp.wowuisync.common.util.Usernames;

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
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.UUID;

/** Join with invite code (DESIGN.md §8). Only the public key is ever sent. */
public final class JoinInviteDialog extends JDialog {
    private final ClientApp app;
    private final JTextArea code = new JTextArea(3, 40);
    private final JTextField username = new JTextField(20);
    private final JRadioButton useKey = new JRadioButton("Generate device key (recommended), or choose a key");
    private final JRadioButton usePw = new JRadioButton("Password");
    private final JTextField keyPath = new JTextField(24);
    private final JPasswordField pw1 = new JPasswordField(20);
    private final JPasswordField pw2 = new JPasswordField(20);
    private final JCheckBox savePw = new JCheckBox("Save in OS credential vault");
    private final JTextField email = new JTextField(24);
    private final JButton join = new JButton("Join");
    private ConnectionRecord result;

    private JoinInviteDialog(Window owner, ClientApp app) {
        super(owner, "Join with invite code", ModalityType.APPLICATION_MODAL);
        this.app = app;
        code.setLineWrap(true);
        ButtonGroup g = new ButtonGroup();
        g.add(useKey);
        g.add(usePw);
        useKey.setSelected(true);
        JButton browse = new JButton("Browse…");
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(new File(System.getProperty("user.home"), ".ssh"));
            fc.setFileHidingEnabled(false);
            fc.setDialogTitle("Choose your OpenSSH private key (only its public key is sent)");
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) keyPath.setText(fc.getSelectedFile().getAbsolutePath());
        });
        JPanel keyRow = new JPanel(new BorderLayout(6, 0));
        keyRow.add(keyPath, BorderLayout.CENTER);
        keyRow.add(browse, BorderLayout.EAST);
        JPanel method = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        method.add(useKey);
        method.add(Box.createHorizontalStrut(12));
        method.add(usePw);
        useKey.addActionListener(e -> update(keyRow));
        usePw.addActionListener(e -> update(keyRow));
        Forms f = new Forms()
                .add("Invite code:", new JScrollPane(code))
                .add("Username:", username)
                .add("Log in with:", method)
                .add("Key file:", keyRow)
                .add("Password:", pw1)
                .add("Repeat password:", pw2)
                .add("", savePw)
                .add("Email (optional):", email);
        f.full(new JLabel("<html><small>Usernames: 3-32 letters, digits, '_' or '-'. Email is used only for password reset.</small></html>"));
        update(keyRow);
        JButton cancel = new JButton("Cancel");
        join.addActionListener(e -> join());
        cancel.addActionListener(e -> dispose());
        JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        b.add(join);
        b.add(cancel);
        JPanel root = new JPanel(new BorderLayout());
        root.add(f.panel(), BorderLayout.CENTER);
        root.add(b, BorderLayout.SOUTH);
        root.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        setContentPane(root);
        pack();
        setLocationRelativeTo(owner);
    }

    private void update(JPanel keyRow) {
        boolean k = useKey.isSelected();
        for (java.awt.Component c : keyRow.getComponents()) c.setEnabled(k);
        pw1.setEnabled(!k);
        pw2.setEnabled(!k);
        savePw.setEnabled(!k);
    }

    private void join() {
        InviteCode ic;
        try {
            ic = InviteCode.decode(code.getText());
        } catch (IllegalArgumentException e) {
            Dialogs.error(this, "This invite code is not valid. Copy the whole code, starting with wus1-.");
            return;
        }
        String u = username.getText().trim();
        String um = Usernames.validationMessage(u);
        if (um != null) {
            Dialogs.error(this, um);
            return;
        }
        String publicKey = null;
        String password = null;
        Path key = null;
        if (useKey.isSelected()) {
            try { key=keyPath.getText().isBlank()?io.github.azotorp.wowuisync.client.crypto.GeneratedKeys.create(app.paths().root().resolve("keys")):Paths.get(keyPath.getText().trim()); }
            catch(Exception e) { Dialogs.error(this,e);return; }
            try {
                publicKey = loadPublic(key);
            } catch (CancelledException e) {
                return;
            } catch (Exception e) {
                Dialogs.error(this, e);
                return;
            }
        } else {
            if (pw1.getPassword().length < 8) {
                Dialogs.error(this, "Passwords must be at least 8 characters.");
                return;
            }
            if (!Arrays.equals(pw1.getPassword(), pw2.getPassword())) {
                Dialogs.error(this, "The passwords do not match.");
                return;
            }
            password = new String(pw1.getPassword());
        }
        String pk = publicKey;
        String pw = password;
        Path keyFile = key;
        join.setEnabled(false);
        String connectionId=UUID.randomUUID().toString();
        Bg.run(() -> {
            ServerConnection c = ServerConnection.open(ic.host(), ic.port(), app.db(), ic.fingerprint(), new UiCallbacks(this),
                    app.config().deviceId, app.config().deviceNickname);
            try {
                Msg.AccountInfo info=c.call(new Msg.InviteRedeem(ic.secret(),u,pw,pk,email.getText().trim()),Msg.AccountInfo.class);
                ConnectionRecord login=new ConnectionRecord(connectionId,info.username()+"@"+ic.host(),ic.host(),ic.port(),info.username(),
                    keyFile!=null?ConnectionRecord.AUTH_KEY:ConnectionRecord.AUTH_PASSWORD,pw,keyFile==null?null:keyFile.toString());
                c.login(login,new UiCallbacks(this));
                return new Joined(c,info);
            } catch(Exception e) { c.close();throw e; }
        }, joined -> {
            Msg.AccountInfo info=joined.info();
            ConnectionRecord rec = new ConnectionRecord(connectionId, info.username() + "@" + ic.host(), ic.host(), ic.port(),
                    info.username(), keyFile != null ? ConnectionRecord.AUTH_KEY : ConnectionRecord.AUTH_PASSWORD,
                    keyFile == null && savePw.isSelected() ? pw : null, keyFile == null ? null : keyFile.toString());
            try {
                app.db().saveConnection(rec);
            } catch (Exception e) {
                try {
                    app.db().saveConnection(new ConnectionRecord(rec.id(),rec.name(),rec.host(),rec.port(),rec.username(),rec.authType(),null,rec.keyPath()));
                    Dialogs.warn(this,"Account created and signed in. The OS password vault was unavailable; the saved connection will prompt for your password next time.");
                } catch(Exception storageError) {joined.connection().close();Dialogs.error(this,"Account created, but connection settings could not be saved. Add an existing-account connection using the same credential.");dispose();return;}
            }
            app.setConnection(joined.connection(),new ConnectionRecord(rec.id(),rec.name(),rec.host(),rec.port(),rec.username(),rec.authType(),null,rec.keyPath()));
            Dialogs.info(this,"Account created and signed in."+(keyFile==null?"":" Your device key is stored locally. Keep a secure recovery copy before moving to another PC."));
            result = rec;
            dispose();
        }, e -> {
            join.setEnabled(true);
            if (!(e instanceof CancelledException)) Dialogs.error(this, e);
        });
    }

    private record Joined(ServerConnection connection,Msg.AccountInfo info) {}

    private String loadPublic(Path key) throws Exception {
        char[] pass = null;
        while (true) {
            try {
                return PrivateKeys.load(key, pass).publicKeyLine();
            } catch (PrivateKeys.PassphraseNeeded e) {
                pass = Dialogs.askSecret(this, (e.wrong() ? "Wrong passphrase. " : "") + "Passphrase for " + key.getFileName() + ":");
                if (pass == null) throw new CancelledException();
            }
        }
    }

    public static ConnectionRecord show(Window owner, ClientApp app) {
        JoinInviteDialog d = new JoinInviteDialog(owner, app);
        d.setVisible(true);
        return d.result;
    }
}
