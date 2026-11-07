package io.github.azotorp.wowuisync.client.ui;

import io.github.azotorp.wowuisync.client.ClientApp;
import io.github.azotorp.wowuisync.client.data.ConnectionRecord;
import io.github.azotorp.wowuisync.client.net.ServerConnection;
import io.github.azotorp.wowuisync.client.util.CancelledException;
import io.github.azotorp.wowuisync.common.Defaults;
import io.github.azotorp.wowuisync.common.protocol.Msg;

import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JOptionPane;
import javax.swing.SpinnerNumberModel;
import java.awt.Window;

/** Forgot password: email a 6-digit code, then set a new password (DESIGN.md §7). */
public final class ForgotPasswordDialog {
    private ForgotPasswordDialog() {}

    public static void show(Window owner, ClientApp app, ConnectionRecord selected) {
        JTextField host = new JTextField(selected == null ? Defaults.CLIENT_HOST : selected.host(), 20);
        JSpinner port = new JSpinner(new SpinnerNumberModel(selected == null ? Defaults.PORT : selected.port(), 1, 65535, 1));
        port.setEditor(new JSpinner.NumberEditor(port, "#"));
        JTextField user = new JTextField(selected == null ? "" : selected.username(), 20);
        JPanel p = new Forms().add("Host:", host).add("Port:", port).add("Username or email:", user).panel();
        if (JOptionPane.showConfirmDialog(owner, p, "Forgot password", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        String h = host.getText().trim();
        int pt = (Integer) port.getValue();
        String u = user.getText().trim();
        if (h.isEmpty() || u.isEmpty()) return;
        Bg.run(() -> {
            try (ServerConnection c = open(owner, app, h, pt)) {
                return c.call(new Msg.ResetRequest(u), Msg.Ok.class);
            }
        }, ok -> step2(owner, app, h, pt, u, ok.message()), e -> {
            if (!(e instanceof CancelledException)) Dialogs.error(owner, e);
        });
    }

    private static ServerConnection open(Window owner, ClientApp app, String host, int port) throws Exception {
        return ServerConnection.open(host, port, app.db(), null, new UiCallbacks(owner), app.config().deviceId, app.config().deviceNickname);
    }

    private static void step2(Window owner, ClientApp app, String host, int port, String user, String message) {
        String code = Dialogs.askText(owner, message + "\n\nEnter the 6-digit code from the email:", "");
        if (code == null || code.isBlank()) return;
        char[] pw = Dialogs.askNewPassword(owner, "Choose a new password for " + user + ".");
        if (pw == null) return;
        Bg.run(() -> {
            try (ServerConnection c = open(owner, app, host, port)) {
                return c.call(new Msg.ResetConfirm(user, code.trim(), new String(pw)), Msg.Ok.class);
            }
        }, ok -> Dialogs.info(owner, ok.message() + "\n\nIf this connection saves its password, edit it to use the new one."),
                e -> {
                    if (!(e instanceof CancelledException)) Dialogs.error(owner, e);
                });
    }
}
