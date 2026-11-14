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
    public final java.util.concurrent.Semaphore handshakes;
    public final java.util.concurrent.ThreadPoolExecutor mailQueue=new java.util.concurrent.ThreadPoolExecutor(2,2,30,java.util.concurrent.TimeUnit.SECONDS,
        new java.util.concurrent.ArrayBlockingQueue<>(64),r->{Thread t=new Thread(r,"mail");t.setDaemon(true);return t;});
    private volatile boolean stopping;

    public ServerContext(DataLayout layout, ServerConfig config, ServerDb db, SSLContext ssl, String fingerprint) {
        this.layout = layout;
        this.config = config;
        this.db = db;
        this.ssl = ssl;
        this.fingerprint = fingerprint;
        this.handshakes=new java.util.concurrent.Semaphore(config.maxConcurrentHandshakes);
        this.smtp = new SmtpSender(config.smtp);
    }

    public boolean stopping() {
        return stopping;
    }

    public void setStopping() {
        stopping = true;
        mailQueue.shutdown();
    }

    public String publicHost() {
        return config.publicHost == null || config.publicHost.isBlank() ? "localhost" : config.publicHost;
    }
}
