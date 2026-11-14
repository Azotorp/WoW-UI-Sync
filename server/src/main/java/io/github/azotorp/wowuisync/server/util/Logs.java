package io.github.azotorp.wowuisync.server.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Logging setup: console + rotating {@code logs/server.log} (10 MB × 5). */
public final class Logs {
    public static final Logger LOG = Logger.getLogger("wowuisync");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Logs() {}

    private static FileHandler fileHandler;

    /** Release our rotating log and its file lock when the server stops. */
    public static synchronized void close() {
        if (fileHandler != null) {
            Logger.getLogger("").removeHandler(fileHandler);
            fileHandler.close();
            fileHandler = null;
        }
    }

    public static synchronized void init(Path logsDir, String level) {
        close();
        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers()) root.removeHandler(h);
        Formatter f = new Formatter() {
            @Override
            public String format(LogRecord r) {
                StringBuilder sb = new StringBuilder();
                sb.append(LocalDateTime.now().format(TS)).append(' ').append(String.format("%-7s", r.getLevel().getName()))
                        .append(' ').append(formatMessage(r)).append(System.lineSeparator());
                if (r.getThrown() != null) {
                    StringWriter sw = new StringWriter();
                    r.getThrown().printStackTrace(new PrintWriter(sw));
                    sb.append(sw);
                }
                return sb.toString();
            }
        };
        Level lvl;
        try {
            lvl = Level.parse(level == null ? "INFO" : level.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            lvl = Level.INFO;
        }
        ConsoleHandler ch = new ConsoleHandler();
        ch.setFormatter(f);
        ch.setLevel(lvl);
        root.addHandler(ch);
        if (logsDir != null) {
            try {
                Files.createDirectories(logsDir);
                FileHandler fh = new FileHandler(logsDir.resolve("server.log").toString().replace("%", "%%"), 10 * 1024 * 1024, 5, true);
                fh.setFormatter(f);
                fh.setLevel(lvl);
                fh.setEncoding("UTF-8");
                root.addHandler(fh);
                fileHandler = fh;
            } catch (IOException e) {
                System.err.println("Could not open log file in " + logsDir + ": " + e.getMessage());
            }
        }
        root.setLevel(lvl);
        LOG.setLevel(lvl);
    }
}
