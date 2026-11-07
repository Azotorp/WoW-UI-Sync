package io.github.azotorp.wowuisync.client.ui;

import io.github.azotorp.wowuisync.client.ClientApp;
import io.github.azotorp.wowuisync.client.data.ConnectionRecord;
import io.github.azotorp.wowuisync.common.protocol.Msg;
import io.github.azotorp.wowuisync.common.util.Sizes;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Window;

/** My account (DESIGN.md §17.5): every account can edit its own email and password or key. */
public final class MyAccountDialog {
    private MyAccountDialog() {}

    private static Msg.AccountInfo me(ClientApp app) {
        for (Msg.AccountInfo a : app.login().accessibleAccounts()) if (a.id().equals(app.login().accountId())) return a;
        return null;
    }

    public static void show(Window owner, ClientApp app) {
        Msg.AccountInfo me = me(app);
        if (me == null) return;
        JButton email = new JButton("Change email…");
        JButton cred = new JButton("Change password or key…");
        JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        b.add(email);
        b.add(cred);
        Forms f = new Forms()
                .add("Username:", new JLabel(me.username()))
                .add("Role:", new JLabel(me.role()))
                .add("Email:", new JLabel(me.email() == null ? "(none)" : me.email()))
                .add("Login method:", new JLabel("key".equals(me.authMethod()) ? "public key (" + me.keyType() + ")" : "password"))
                .add("Quota:", new JLabel(me.quotaMb() == null ? "unlimited" : me.quotaMb() + " MB"))
                .add("Stored:", new JLabel(Sizes.human(me.usedBytes())))
                .full(b);
        email.addActionListener(e -> {
            String v = Dialogs.askText(owner, "Your email (used only for password reset; blank to remove):", me.email() == null ? "" : me.email());
            if (v == null) return;
            BusyDialog.run(owner, "Saving", d -> () -> app.connection().call(new Msg.AccountUpdate(me.id(), v.trim(), null, null, null),
                    Msg.AccountInfo.class), a -> refresh(app, owner, "Email saved."));
        });
        cred.addActionListener(e -> changeCredential(owner, app, me));
        JOptionPane.showMessageDialog(owner, f.panel(), "My account", JOptionPane.PLAIN_MESSAGE);
    }

    private static void refresh(ClientApp app, Component owner, String msg) {
        Window w = owner instanceof Window ow ? ow : javax.swing.SwingUtilities.getWindowAncestor(owner);
        BusyDialog.run(w, "Refreshing",
                d -> () -> app.connection().call(new Msg.AccountList(), Msg.AccountListResp.class), r -> {
                    app.updateAccessible(r.accounts());
                    if (msg != null) Dialogs.info(owner, msg);
                });
    }

    /** Changes the logged-in account's own password or key; offers to update the saved connection. */
    static void changeCredential(Component owner, ClientApp app, Msg.AccountInfo me) {
        CredentialInput cred = new CredentialInput("key".equals(me.authMethod()));
        Forms f = new Forms();
        f.full(new JLabel("New password or public key for " + me.username() + ". Your other sessions end."));
        cred.addTo(f);
        while (true) {
            if (JOptionPane.showConfirmDialog(owner, f.panel(), "Change password or key", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            try {
                CredentialInput.Value v = cred.value();
                Window w = owner instanceof Window ow ? ow : javax.swing.SwingUtilities.getWindowAncestor(owner);
                BusyDialog.run(w, "Saving", d -> () -> app.connection().call(new Msg.AccountSetCredential(me.id(), v.password(), v.publicKey()),
                        Msg.AccountInfo.class), a -> {
                    ConnectionRecord rec = app.connectionRecord();
                    String note = "Saved.";
                    try {
                        if (rec != null && v.password() != null && !rec.usesKey() && rec.password() != null
                                && Dialogs.confirm(owner, "Update the password saved in the connection '" + rec.name() + "'?")) {
                            app.db().saveConnection(new ConnectionRecord(rec.id(), rec.name(), rec.host(), rec.port(), rec.username(),
                                    rec.authType(), v.password(), rec.keyPath()));
                        } else if (rec != null && v.publicKey() != null && !rec.usesKey()) {
                            note = "Saved. Your account now logs in with a key: edit the connection '" + rec.name()
                                    + "' and choose the matching private key file.";
                        } else if (rec != null && v.password() != null && rec.usesKey()) {
                            note = "Saved. Your account now logs in with a password: edit the connection '" + rec.name() + "'.";
                        }
                    } catch (Exception ex) {
                        Dialogs.error(owner, ex);
                    }
                    refresh(app, owner, note);
                });
                return;
            } catch (IllegalArgumentException e) {
                Dialogs.error(owner, e.getMessage());
            }
        }
    }
}
