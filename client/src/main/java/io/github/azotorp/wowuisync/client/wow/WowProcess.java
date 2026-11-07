package io.github.azotorp.wowuisync.client.wow;

import io.github.azotorp.wowuisync.common.profile.Profiles;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/** Is this flavor's WoW running? (DESIGN.md §16) */
public final class WowProcess {
    public enum State { RUNNING, NOT_RUNNING, UNKNOWN }

    private WowProcess() {}

    public static State check(Path flavorFolder) {
        String folder = norm(flavorFolder.toAbsolutePath().normalize().toString());
        if (!folder.endsWith("/")) folder += "/";
        String folderName = flavorFolder.getFileName().toString().toLowerCase(Locale.ROOT);
        int withInfo = 0;
        int total = 0;
        try {
            for (ProcessHandle ph : (Iterable<ProcessHandle>) ProcessHandle.allProcesses()::iterator) {
                total++;
                ProcessHandle.Info info;
                try {
                    info = ph.info();
                } catch (RuntimeException e) {
                    continue;
                }
                Optional<String> cmd = info.command();
                if (cmd.isPresent()) {
                    withInfo++;
                    if (norm(cmd.get()).startsWith(folder)) return State.RUNNING;
                }
                Optional<String> line = info.commandLine();
                if (line.isPresent()) {
                    String l = norm(line.get());
                    if (l.contains(folderName)) {
                        for (String exe : Profiles.WOW_EXECUTABLE_NAMES) {
                            if (l.contains(exe.toLowerCase(Locale.ROOT))) return State.RUNNING;
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            return State.UNKNOWN;
        }
        return total > 0 && withInfo == 0 ? State.UNKNOWN : State.NOT_RUNNING;
    }

    private static String norm(String s) {
        return s.replace('\\', '/').toLowerCase(Locale.ROOT);
    }
}
