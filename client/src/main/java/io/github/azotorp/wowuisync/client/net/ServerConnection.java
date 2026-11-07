package io.github.azotorp.wowuisync.client.net;

import io.github.azotorp.wowuisync.client.crypto.PrivateKeys;
import io.github.azotorp.wowuisync.client.data.ClientDb;
import io.github.azotorp.wowuisync.client.data.ConnectionRecord;
import io.github.azotorp.wowuisync.client.util.CancelledException;
import io.github.azotorp.wowuisync.client.util.ClientLog;
import io.github.azotorp.wowuisync.client.util.UserException;
import io.github.azotorp.wowuisync.common.Version;
import io.github.azotorp.wowuisync.common.codec.Codec;
import io.github.azotorp.wowuisync.common.crypto.CertFingerprint;
import io.github.azotorp.wowuisync.common.crypto.KeyLogin;
import io.github.azotorp.wowuisync.common.protocol.Frame;
import io.github.azotorp.wowuisync.common.protocol.MessageType;
import io.github.azotorp.wowuisync.common.protocol.Msg;
import io.github.azotorp.wowuisync.common.protocol.ProtocolException;
import io.github.azotorp.wowuisync.common.protocol.Wire;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.Closeable;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.sql.SQLException;
import java.util.Base64;

/**
 * A control connection to a server: TLS with certificate pinning per host and port, HELLO, login, keep-alive.
 * Data connections are opened with {@link #openData()}.
 */
public final class ServerConnection implements Closeable {
    /** UI hooks; all may block while the user answers. Returning null means cancel. */
    public interface Callbacks {
        /** First connect to this host:port: show the fingerprint and ask whether to trust it. */
        boolean trustNewFingerprint(String hostPort, String fingerprintPretty);

        String askPassword(ConnectionRecord rec, String message);

        char[] askPassphrase(Path keyFile, boolean wrong);
    }

    private final String host;
    private final int port;
    private final String hostPort;
    private final SSLContext sslContext;
    private final String fingerprint;
    private final Wire control;
    private final Msg.HelloOk hello;
    private final Codec codec;
    private volatile Msg.LoginOk login;
    private volatile boolean closed;
    private Thread keepAlive;

    private ServerConnection(String host, int port, SSLContext ctx, String fingerprint, Wire control, Msg.HelloOk hello) {
        this.host = host;
        this.port = port;
        this.hostPort = ConnectionRecord.hostPort(host, port);
        this.sslContext = ctx;
        this.fingerprint = fingerprint;
        this.control = control;
        this.hello = hello;
        this.codec = Codec.byName(hello.codec());
        control.setCodec(codec);
    }

    private static SSLContext trustAllContext() throws Exception {
        // Certificates are self-signed; trust is established by pinning the SHA-256 fingerprint below.
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(null, new TrustManager[] {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, new SecureRandom());
        return ctx;
    }

    private static SSLSocket rawConnect(SSLContext ctx, String host, int port) throws UserException {
        try {
            SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket();
            s.connect(new InetSocketAddress(host, port), 15_000);
            s.setEnabledProtocols(new String[] {"TLSv1.3"});
            s.setTcpNoDelay(true);
            s.setSoTimeout(5 * 60_000);
            s.startHandshake();
            return s;
        } catch (UnknownHostException e) {
            throw new UserException("Unknown host '" + host + "'. Check the server address.", e);
        } catch (ConnectException e) {
            throw new UserException("Could not connect to " + host + ":" + port + ". Is the server running and the port open?", e);
        } catch (SocketTimeoutException e) {
            throw new UserException("Connecting to " + host + ":" + port + " timed out. Check the address, port, and firewall.", e);
        } catch (IOException e) {
            throw new UserException("Could not connect to " + host + ":" + port + ": " + e.getMessage(), e);
        }
    }

    /**
     * Opens a TLS connection, checks the pinned fingerprint (asking on first connect, or pinning {@code expectedFingerprint}
     * from an invite code automatically), and exchanges HELLO.
     */
    public static ServerConnection open(String host, int port, ClientDb db, String expectedFingerprint, Callbacks cb,
                                        String deviceId, String nickname) throws UserException, CancelledException {
        SSLContext ctx;
        try {
            ctx = trustAllContext();
        } catch (Exception e) {
            throw new UserException("TLS is not available: " + e.getMessage(), e);
        }
        SSLSocket s = rawConnect(ctx, host, port);
        try {
            String fp = CertFingerprint.of(s.getSession().getPeerCertificates()[0]);
            String hp = ConnectionRecord.hostPort(host, port);
            String pinned = db.pinned(hp);
            if (pinned != null) {
                if (!pinned.equals(fp)) {
                    s.close();
                    throw new UserException("WARNING: the server's certificate changed!\n\nExpected: " + CertFingerprint.pretty(pinned)
                            + "\nReceived: " + CertFingerprint.pretty(fp)
                            + "\n\nThis can mean someone is intercepting the connection. If the server was reinstalled, "
                            + "check the fingerprint it printed, then use 'Forget certificate' in the connection's settings.");
                }
            } else if (expectedFingerprint != null) {
                if (!CertFingerprint.normalize(expectedFingerprint).equals(fp)) {
                    s.close();
                    throw new UserException("The server's certificate does not match the invite code. Do not continue; ask for a new code.");
                }
                db.pin(hp, fp);
            } else {
                if (!cb.trustNewFingerprint(hp, CertFingerprint.pretty(fp))) {
                    s.close();
                    throw new CancelledException();
                }
                db.pin(hp, fp);
            }
            Wire w = new Wire(s.getInputStream(), s.getOutputStream(), s);
            Frame f = w.call(new Msg.Hello(Version.PROTOCOL_VERSION, Version.APP_VERSION, Codec.supported(), deviceId, nickname));
            if (f.type() == MessageType.LOGIN_FAIL) {
                Msg.LoginFail lf = f.message(Msg.LoginFail.class);
                w.close();
                throw new UserException(lf.message());
            }
            Msg.HelloOk ok = f.message(Msg.HelloOk.class);
            if (ok.protocolVersion() < Version.MIN_PROTOCOL_VERSION) {
                w.close();
                throw new UserException("The server runs WoW UI Sync " + ok.appVersion()
                        + ", which is too old for this client. Ask the server's owner to update it.");
            }
            return new ServerConnection(host, port, ctx, fp, w, ok);
        } catch (UserException | CancelledException e) {
            throw e;
        } catch (SQLException e) {
            closeQuietly(s);
            throw new UserException("Local database error: " + e.getMessage(), e);
        } catch (IOException e) {
            closeQuietly(s);
            throw new UserException("The server did not answer correctly: " + e.getMessage(), e);
        }
    }

    /** Logs in with the connection's credential, prompting for a password or passphrase when needed. */
    public Msg.LoginOk login(ConnectionRecord rec, Callbacks cb) throws UserException, CancelledException {
        try {
            Frame f;
            if (rec.usesKey()) {
                if (rec.keyPath() == null || rec.keyPath().isBlank()) throw new UserException("Choose a private key file for this connection.");
                Path keyFile = Paths.get(rec.keyPath());
                PrivateKeys.LoadedKey key = loadKey(keyFile, cb);
                Frame ch = control.call(new Msg.LoginKeyBegin(rec.username()));
                if (ch.type() == MessageType.LOGIN_FAIL) throw fail(ch.message(Msg.LoginFail.class));
                Msg.LoginKeyChallenge c = ch.message(Msg.LoginKeyChallenge.class);
                byte[] sig = key.sign(KeyLogin.signedData(c.nonce(), c.sessionId(), fingerprint));
                f = control.call(new Msg.LoginKeyProof(Base64.getEncoder().encodeToString(sig)));
            } else {
                String pw = rec.password();
                if (pw == null || pw.isEmpty()) {
                    pw = cb.askPassword(rec, null);
                    if (pw == null) throw new CancelledException();
                }
                f = control.call(new Msg.LoginPassword(rec.username(), pw));
            }
            if (f.type() == MessageType.LOGIN_FAIL) throw fail(f.message(Msg.LoginFail.class));
            login = f.message(Msg.LoginOk.class);
            startKeepAlive();
            ClientLog.LOG.info("Logged in to " + hostPort + " as " + login.username());
            return login;
        } catch (UserException | CancelledException e) {
            throw e;
        } catch (IOException e) {
            throw new UserException("Login failed: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new UserException("Login failed: " + e.getMessage(), e);
        }
    }

    private static UserException fail(Msg.LoginFail lf) {
        return new LoginFailedException(lf.reason(), lf.message());
    }

    /** Login rejected by the server. */
    public static final class LoginFailedException extends UserException {
        private final String reason;

        LoginFailedException(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    private static PrivateKeys.LoadedKey loadKey(Path keyFile, Callbacks cb) throws UserException, CancelledException {
        char[] pass = null;
        boolean wrong = false;
        while (true) {
            try {
                return PrivateKeys.load(keyFile, pass);
            } catch (PrivateKeys.PassphraseNeeded e) {
                wrong = e.wrong();
                pass = cb.askPassphrase(keyFile, wrong);
                if (pass == null) throw new CancelledException();
            } catch (java.nio.file.NoSuchFileException e) {
                throw new UserException("The private key file was not found: " + keyFile);
            } catch (IOException e) {
                throw new UserException(e.getMessage(), e);
            }
        }
    }

    private void startKeepAlive() {
        keepAlive = Thread.ofPlatform().daemon().name("keepalive").start(() -> {
            while (!closed) {
                try {
                    Thread.sleep(60_000);
                    call(new Msg.Ping(), Msg.Ok.class);
                } catch (InterruptedException e) {
                    return;
                } catch (IOException e) {
                    ClientLog.LOG.info("Keep-alive failed: " + e.getMessage());
                    close();
                    return;
                }
            }
        });
    }

    /** Request/response on the control connection. Server errors throw {@link ProtocolException}. */
    public synchronized <T> T call(Object message, Class<T> reply) throws IOException {
        if (closed) throw new IOException("Disconnected from the server.");
        try {
            return control.call(message, reply);
        } catch (ProtocolException e) {
            throw e;
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    public synchronized Frame callFrame(Object message) throws IOException {
        if (closed) throw new IOException("Disconnected from the server.");
        return control.call(message);
    }

    /** Opens a data connection joined to this session. */
    public Wire openData() throws IOException {
        if (login == null) throw new IOException("Not logged in");
        SSLSocket s;
        try {
            s = rawConnect(sslContext, host, port);
        } catch (UserException e) {
            throw new IOException(e.getMessage(), e);
        }
        String fp = CertFingerprint.of(s.getSession().getPeerCertificates()[0]);
        if (!fp.equals(fingerprint)) {
            closeQuietly(s);
            throw new IOException("Server certificate changed during the session.");
        }
        Wire w = new Wire(s.getInputStream(), s.getOutputStream(), s);
        w.setCodec(codec);
        w.call(new Msg.DataJoin(login.sessionId()), Msg.Ok.class);
        return w;
    }

    public String hostPort() { return hostPort; }
    public String host() { return host; }
    public int port() { return port; }
    public String fingerprint() { return fingerprint; }
    public Msg.HelloOk hello() { return hello; }
    public Msg.LoginOk login() { return login; }
    public Codec codec() { return codec; }
    public boolean isClosed() { return closed; }

    public void updateLogin(Msg.LoginOk l) {
        this.login = l;
    }

    @Override
    public void close() {
        closed = true;
        if (keepAlive != null) keepAlive.interrupt();
        control.close();
    }

    private static void closeQuietly(SSLSocket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // ignore
        }
    }
}
