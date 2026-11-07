package io.github.azotorp.wowuisync.server.mail;

import io.github.azotorp.wowuisync.server.config.ServerConfig;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/** Minimal SMTP sender: STARTTLS or SMTPS, AUTH PLAIN or LOGIN (DESIGN.md §3). */
public final class SmtpSender {
    private final ServerConfig.Smtp cfg;

    public SmtpSender(ServerConfig.Smtp cfg) {
        this.cfg = cfg;
    }

    public boolean enabled() {
        return cfg != null && cfg.enabled && cfg.host != null && !cfg.host.isBlank();
    }

    public void send(String to, String subject, String body) throws IOException {
        if (!enabled()) throw new IOException("SMTP is not enabled");
        if (to.contains("\r") || to.contains("\n") || subject.contains("\r") || subject.contains("\n")) {
            throw new IOException("Invalid header value");
        }
        boolean ssl = "ssl".equalsIgnoreCase(cfg.security);
        SSLSocketFactory tlsFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        Socket socket;
        if (ssl) {
            socket = tlsFactory.createSocket();
        } else {
            socket = new Socket();
        }
        socket.connect(new InetSocketAddress(cfg.host, cfg.port), 20_000);
        socket.setSoTimeout(30_000);
        try {
            Conn c = new Conn(socket);
            if (ssl) ((SSLSocket) socket).startHandshake();
            c.expect(220);
            String ehlo = c.cmd("EHLO wow-ui-sync", 250);
            if (!ssl) {
                if (!ehlo.toUpperCase(Locale.ROOT).contains("STARTTLS")) throw new IOException("The SMTP server does not offer STARTTLS");
                c.cmd("STARTTLS", 220);
                SSLSocket tls = (SSLSocket) tlsFactory.createSocket(socket, cfg.host, cfg.port, true);
                tls.startHandshake();
                c = new Conn(tls);
                ehlo = c.cmd("EHLO wow-ui-sync", 250);
            }
            if (cfg.username != null && !cfg.username.isEmpty()) {
                String upper = ehlo.toUpperCase(Locale.ROOT);
                Base64.Encoder b64 = Base64.getEncoder();
                if (upper.contains("PLAIN") || !upper.contains("LOGIN")) {
                    String token = b64.encodeToString(("\0" + cfg.username + "\0" + cfg.password).getBytes(StandardCharsets.UTF_8));
                    c.cmd("AUTH PLAIN " + token, 235);
                } else {
                    c.cmd("AUTH LOGIN", 334);
                    c.cmd(b64.encodeToString(cfg.username.getBytes(StandardCharsets.UTF_8)), 334);
                    c.cmd(b64.encodeToString(cfg.password.getBytes(StandardCharsets.UTF_8)), 235);
                }
            }
            String from = cfg.from == null || cfg.from.isBlank() ? cfg.username : cfg.from;
            c.cmd("MAIL FROM:<" + address(from) + ">", 250);
            c.cmd("RCPT TO:<" + address(to) + ">", 250, 251);
            c.cmd("DATA", 354);
            StringBuilder msg = new StringBuilder();
            msg.append("From: ").append(from).append("\r\n");
            msg.append("To: ").append(to).append("\r\n");
            msg.append("Subject: ").append(subject).append("\r\n");
            msg.append("Date: ").append(ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME)).append("\r\n");
            msg.append("Message-ID: <").append(UUID.randomUUID()).append("@wow-ui-sync>\r\n");
            msg.append("MIME-Version: 1.0\r\n");
            msg.append("Content-Type: text/plain; charset=UTF-8\r\n");
            msg.append("Content-Transfer-Encoding: 8bit\r\n\r\n");
            for (String line : body.split("\r?\n", -1)) {
                if (line.startsWith(".")) msg.append('.');
                msg.append(line).append("\r\n");
            }
            msg.append(".");
            c.cmd(msg.toString(), 250);
            try {
                c.cmd("QUIT", 221);
            } catch (IOException ignored) {
                // some servers just close
            }
        } finally {
            socket.close();
        }
    }

    private static String address(String s) {
        int lt = s.indexOf('<');
        int gt = s.indexOf('>');
        return lt >= 0 && gt > lt ? s.substring(lt + 1, gt).trim() : s.trim();
    }

    private static final class Conn {
        private final BufferedReader in;
        private final OutputStream out;

        Conn(Socket s) throws IOException {
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = s.getOutputStream();
        }

        String cmd(String line, int... ok) throws IOException {
            out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return expect(ok);
        }

        String expect(int... ok) throws IOException {
            StringBuilder all = new StringBuilder();
            String line;
            int code;
            do {
                line = in.readLine();
                if (line == null) throw new IOException("SMTP connection closed");
                all.append(line).append('\n');
                if (line.length() < 3) throw new IOException("Bad SMTP reply: " + line);
                code = Integer.parseInt(line.substring(0, 3));
            } while (line.length() > 3 && line.charAt(3) == '-');
            for (int c : ok) if (c == code) return all.toString();
            throw new IOException("SMTP error: " + all.toString().trim());
        }
    }
}
