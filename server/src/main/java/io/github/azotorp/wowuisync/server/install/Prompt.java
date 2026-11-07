package io.github.azotorp.wowuisync.server.install;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Console prompts; passwords are hidden when a real console is available. */
public final class Prompt {
    private final Console console = System.console();
    private final BufferedReader reader = console == null
            ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)) : null;
    private boolean warnedEcho;

    public void println(String s) {
        System.out.println(s);
    }

    public String line(String prompt, String def) throws IOException {
        String p = def == null || def.isEmpty() ? prompt + ": " : prompt + " [" + def + "]: ";
        String s;
        if (console != null) {
            s = console.readLine("%s", p);
        } else {
            System.out.print(p);
            System.out.flush();
            s = reader.readLine();
        }
        if (s == null) throw new IOException("Input closed");
        s = s.trim();
        return s.isEmpty() && def != null ? def : s;
    }

    public char[] password(String prompt) throws IOException {
        if (console != null) {
            char[] c = console.readPassword("%s: ", prompt);
            if (c == null) throw new IOException("Input closed");
            return c;
        }
        if (!warnedEcho) {
            System.out.println("(No console detected: the password will be visible while you type.)");
            warnedEcho = true;
        }
        System.out.print(prompt + ": ");
        System.out.flush();
        String s = reader.readLine();
        if (s == null) throw new IOException("Input closed");
        return s.toCharArray();
    }

    public boolean yesNo(String prompt, boolean def) throws IOException {
        while (true) {
            String s = line(prompt + (def ? " [Y/n]" : " [y/N]"), "").toLowerCase(java.util.Locale.ROOT);
            if (s.isEmpty()) return def;
            if (s.equals("y") || s.equals("yes")) return true;
            if (s.equals("n") || s.equals("no")) return false;
        }
    }
}
