package io.github.azotorp.wowuisync.client.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** {@code logs/client.log} (rotating). */
public final class ClientLog {
    public static final Logger LOG = Logger.getLogger("wowuisync.client");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ClientLog() {}

    public static void init(Path logs) {
        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers()) root.removeHandler(h);
        try {
            Files.createDirectories(logs);
            FileHandler fh = new FileHandler(logs.resolve("client.log").toString().replace("%", "%%"), 5 * 1024 * 1024, 3, true);
            fh.setEncoding("UTF-8");
            fh.setFormatter(new Formatter() {
                @Override
                public String format(LogRecord r) {
                    StringBuilder sb = new StringBuilder(LocalDateTime.now().format(TS)).append(' ')
                            .append(r.getLevel().getName()).append(' ').append(formatMessage(r)).append(System.lineSeparator());
                    if (r.getThrown() != null) {
                        StringWriter sw = new StringWriter();
                        r.getThrown().printStackTrace(new PrintWriter(sw));
                        sb.append(sw);
                    }
                    return sb.toString();
                }
            });
            root.addHandler(fh);
        } catch (IOException e) {
            System.err.println("Cannot open client log: " + e.getMessage());
        }
        root.setLevel(Level.INFO);
    }
}
