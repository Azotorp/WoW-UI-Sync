package io.github.azotorp.wowuisync.server.net;

import io.github.azotorp.wowuisync.server.util.Logs;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** TLS 1.3 listener on one port; one virtual thread per connection. */
public final class Listener implements AutoCloseable {
    private final ServerContext ctx;
    private final ConnectionHandler.Services services;
    private final SSLServerSocket server;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private Thread acceptThread;
    private final java.util.Set<SSLSocket> sockets=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<String,Integer> perIp=new java.util.HashMap<>();
    private synchronized boolean reserve(SSLSocket s) {
        String ip=s.getInetAddress().getHostAddress();
        if(sockets.size()>=ctx.config.maxActiveConnections || perIp.getOrDefault(ip,0)>=ctx.config.maxConnectionsPerIp) return false;
        sockets.add(s);perIp.merge(ip,1,Integer::sum);return true;
    }
    private synchronized void release(SSLSocket s) {
        sockets.remove(s);String ip=s.getInetAddress().getHostAddress();perIp.computeIfPresent(ip,(k,v)->v<=1?null:v-1);
    }

    public Listener(ServerContext ctx, ConnectionHandler.Services services) throws IOException {
        this.ctx = ctx;
        this.services = services;
        this.server = (SSLServerSocket) ctx.ssl.getServerSocketFactory().createServerSocket();
        server.setReuseAddress(true);
        server.setEnabledProtocols(new String[] {"TLSv1.3"});
        server.bind(new InetSocketAddress(InetAddress.getByName(ctx.config.bindAddress), ctx.config.port), 128);
    }

    public void start() {
        acceptThread = Thread.ofPlatform().name("accept").start(() -> {
            Logs.LOG.info("Listening on " + ctx.config.bindAddress + ":" + ctx.config.port + " (TLS 1.3)");
            while (!server.isClosed()) {
                try {
                    SSLSocket s = (SSLSocket) server.accept();
                    if(!reserve(s)) { s.close();continue; }
                    pool.submit(()-> { try { new ConnectionHandler(ctx,services,s).run(); } finally { release(s); } });
                } catch (IOException e) {
                    if (!server.isClosed()) Logs.LOG.warning("Accept failed: " + e.getMessage());
                }
            }
        });
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // ignore
        }
        for(var socket:sockets) try { socket.close(); } catch(IOException ignored) {}
        pool.shutdown();
    }

    public void awaitConnections(long millis) throws InterruptedException {
        pool.awaitTermination(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
}
