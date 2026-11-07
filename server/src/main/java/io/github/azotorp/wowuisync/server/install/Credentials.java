package io.github.azotorp.wowuisync.server.install;

import io.github.azotorp.wowuisync.common.crypto.Passwords;
import io.github.azotorp.wowuisync.common.crypto.SshPublicKeys;

import java.io.IOException;
import java.util.Arrays;

/** Interactive credential entry shared by install and reset-root. */
final class Credentials {
    record Choice(SshPublicKeys.ParsedKey key, Passwords.Hash password) {}

    private Credentials() {}

    /** Asks for a public key line (Enter to skip), then a password twice if skipped. */
    static Choice ask(Prompt in, String who, boolean allowKey) throws IOException {
        if (allowKey) {
            while (true) {
                String line = in.line("Paste " + who + "'s OpenSSH public key line (or press Enter to use a password instead)", "");
                if (line.isEmpty()) break;
                try {
                    SshPublicKeys.ParsedKey k = SshPublicKeys.parse(line);
                    in.println("Accepted " + k.type() + " key (" + k.bits() + " bits). " + who + " will log in with this key only.");
                    return new Choice(k, null);
                } catch (IllegalArgumentException e) {
                    in.println("  " + e.getMessage());
                }
            }
        }
        while (true) {
            char[] a = in.password("New password for " + who + " (min 8 characters)");
            String msg = Passwords.validationMessage(a);
            if (msg != null) {
                in.println("  " + msg);
                continue;
            }
            char[] b = in.password("Repeat the password");
            if (!Arrays.equals(a, b)) {
                in.println("  The passwords do not match. Try again.");
                continue;
            }
            Passwords.Hash h = Passwords.hash(a);
            Arrays.fill(a, '\0');
            Arrays.fill(b, '\0');
            return new Choice(null, h);
        }
    }
}
