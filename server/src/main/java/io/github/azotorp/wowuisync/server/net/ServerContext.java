package io.github.azotorp.wowuisync.server.net;

import io.github.azotorp.wowuisync.server.config.DataLayout;
import io.github.azotorp.wowuisync.server.config.ServerConfig;
import io.github.azotorp.wowuisync.server.db.ServerDb;
import io.github.azotorp.wowuisync.server.mail.SmtpSender;

import javax.net.ssl.SSLContext;

/** Shared server state handed to connection handlers and services. */
public final class ServerContext {
    public final DataLayout layout;
    public final ServerConfig config;
    public final ServerDb db;
    public final SSLContext ssl;
    public final String fingerprint;
    public final Sessions sessions = new Sessions();
    public final SmtpSender smtp;
    private volatile boolean stopping;

    public ServerContext(DataLayout layout, ServerConfig config, ServerDb db, SSLContext ssl, String fingerprint) {
        this.layout = layout;
        this.config = config;
        this.db = db;
        this.ssl = ssl;
        this.fingerprint = fingerprint;
        this.smtp = new SmtpSender(config.smtp);
    }

    public boolean stopping() {
        return stopping;
    }

    public void setStopping() {
        stopping = true;
    }

    public String publicHost() {
        return config.publicHost == null || config.publicHost.isBlank() ? "localhost" : config.publicHost;
    }
}
