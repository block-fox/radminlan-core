package dev.radminlan.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded single-use loopback listeners for game sockets and CLI forwarding. */
public final class TcpForwarder implements AutoCloseable {
    interface StreamPair extends AutoCloseable {
        InputStream input();
        OutputStream output();
        void shutdownOutput() throws IOException;
        @Override void close();
    }
    @FunctionalInterface interface Opener { StreamPair open(Inet4Address address, int port) throws IOException; }

    private final Opener opener;
    private final Semaphore capacity = new Semaphore(24);
    private final Set<Lease> active = ConcurrentHashMap.newKeySet();
    private boolean closed;

    public TcpForwarder(CoreSession session) {
        this((ip, port) -> {
            var connection = session.openTcp(ip, port);
            return new StreamPair() {
                @Override public InputStream input() { return connection.input(); }
                @Override public OutputStream output() { return connection.output(); }
                @Override public void shutdownOutput() throws IOException { connection.shutdownOutput(); }
                @Override public void close() { connection.close(); }
            };
        });
    }

    TcpForwarder(Opener opener) { this.opener = opener; }

    public synchronized Lease prepare(Inet4Address address, int port) throws IOException {
        if (address == null || address.getAddress()[0] != 26 || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Expected a virtual IPv4 address and port");
        }
        if (closed || !capacity.tryAcquire()) throw new IOException("Local forwarding capacity unavailable");
        ServerSocket server = new ServerSocket();
        try {
            InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
            server.bind(new InetSocketAddress(loopback, 0), 1);
            server.setSoTimeout(30_000);
        } catch (IOException | RuntimeException error) {
            closeQuietly(server);
            capacity.release();
            throw error;
        }
        Lease lease = new Lease(server);
        active.add(lease);
        Thread.ofVirtual().name("radminlan-forward").start(() -> serve(lease, address, port));
        return lease;
    }

    private void serve(Lease lease, Inet4Address address, int port) {
        try {
            Socket socket = lease.server.accept();
            if (!lease.attach(socket)) return;
            lease.server.close();
            socket.setTcpNoDelay(true);
            StreamPair remote = opener.open(address, port);
            if (!lease.attach(remote)) return;
            AtomicInteger pumps = new AtomicInteger(2);
            Thread.ofVirtual().name("radminlan-forward-upload").start(() -> {
                try {
                    socket.getInputStream().transferTo(remote.output());
                    remote.shutdownOutput();
                } catch (IOException | RuntimeException e) { lease.close(); }
                finally { if (pumps.decrementAndGet() == 0) lease.close(); }
            });
            try {
                remote.input().transferTo(socket.getOutputStream());
                socket.shutdownOutput();
            } finally { if (pumps.decrementAndGet() == 0) lease.close(); }
        } catch (IOException | RuntimeException e) { lease.close(); }
    }

    public synchronized void cancelAll() { for (Lease lease : active.toArray(Lease[]::new)) lease.close(); }
    @Override public synchronized void close() { closed = true; cancelAll(); }

    public final class Lease implements AutoCloseable {
        private final ServerSocket server;
        private final InetSocketAddress endpoint;
        private final AtomicBoolean done = new AtomicBoolean();
        private Socket socket;
        private StreamPair remote;

        private Lease(ServerSocket server) {
            this.server = server;
            endpoint = (InetSocketAddress) server.getLocalSocketAddress();
        }
        public InetSocketAddress endpoint() { return endpoint; }
        private synchronized boolean attach(Socket candidate) {
            if (done.get()) { closeQuietly(candidate); return false; }
            socket = candidate;
            return true;
        }
        private synchronized boolean attach(StreamPair candidate) {
            if (done.get()) { closeQuietly(candidate); return false; }
            remote = candidate;
            return true;
        }
        @Override public synchronized void close() {
            if (!done.compareAndSet(false, true)) return;
            closeQuietly(server);
            closeQuietly(socket);
            closeQuietly(remote);
            active.remove(this);
            capacity.release();
        }
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) return;
        try { resource.close(); } catch (Exception ignored) { }
    }
}
