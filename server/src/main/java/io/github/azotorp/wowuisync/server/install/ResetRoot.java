package io.github.azotorp.wowuisync.server.install;

import io.github.azotorp.wowuisync.server.config.DataLayout;
import io.github.azotorp.wowuisync.server.db.AccountRow;
import io.github.azotorp.wowuisync.server.db.ServerDb;
import io.github.azotorp.wowuisync.server.security.Permissions;

import java.nio.file.Files;

/** The {@code reset-root} subcommand. Works while the server runs: sessions check the credential version. */
public final class ResetRoot {
    private ResetRoot() {}

    public static void run(DataLayout layout) throws Exception {
        io.github.azotorp.wowuisync.server.util.Logs.init(null, "WARNING");
        Prompt in = new Prompt();
        if (!Files.exists(layout.db())) {
            in.println("No database in " + layout.root() + ". Run install first.");
            return;
        }
        try (ServerDb db = ServerDb.open(layout.db(), layout.dbBackups())) {
            AccountRow root = db.root().orElse(null);
            if (root == null) {
                in.println("No root account exists. Run install.");
                return;
            }
            in.println("Reset root's credentials. Root currently logs in with a " + (root.usesKey() ? "public key" : "password") + ".");
            boolean key = in.yesNo("Use a public key (instead of a password)?", root.usesKey());
            Credentials.Choice c = Credentials.ask(in, "root", key);
            if (c.key() != null) {
                Files.createDirectories(layout.keys());
                Files.writeString(layout.keyFile(root.id()), c.key().line() + "\n");
                db.setKey(root.id(), c.key().type());
            } else {
                db.setPassword(root.id(), c.password().hashB64(), c.password().saltB64(), c.password().iterations());
                Files.deleteIfExists(layout.keyFile(root.id()));
            }
            Permissions.lockDown(layout, true);
            in.println("Root's credentials were replaced. Any active root sessions end.");
        }
    }
}
