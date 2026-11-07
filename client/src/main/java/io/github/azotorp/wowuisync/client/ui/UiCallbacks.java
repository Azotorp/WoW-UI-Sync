package io.github.azotorp.wowuisync.client.ui;

import io.github.azotorp.wowuisync.client.data.ConnectionRecord;
import io.github.azotorp.wowuisync.client.net.ServerConnection;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.nio.file.Path;

/** {@link ServerConnection.Callbacks} that ask the user with dialogs. */
public final class UiCallbacks implements ServerConnection.Callbacks {
    private final Component parent;

    public UiCallbacks(Component parent) {
        this.parent = parent;
    }

    @Override
    public boolean trustNewFingerprint(String hostPort, String fp) {
        return Dialogs.onEdt(() -> JOptionPane.showConfirmDialog(parent,
                Dialogs.wrap("First connection to " + hostPort + ".\n\nThe server's certificate fingerprint (SHA-256) is:\n\n" + fp
                        + "\n\nCompare it with the fingerprint printed by the server's install (or ask the server's owner). "
                        + "Trust this server?"),
                "Confirm server identity", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION);
    }

    @Override
    public String askPassword(ConnectionRecord rec, String message) {
        char[] pw = Dialogs.onEdt(() -> Dialogs.askSecret(parent, (message == null ? "" : message + " ")
                + "Password for " + rec.username() + " on " + rec.host() + ":"));
        return pw == null ? null : new String(pw);
    }

    @Override
    public char[] askPassphrase(Path keyFile, boolean wrong) {
        return Dialogs.onEdt(() -> Dialogs.askSecret(parent, (wrong ? "Wrong passphrase. " : "")
                + "Passphrase for " + keyFile.getFileName() + ":"));
    }
}
