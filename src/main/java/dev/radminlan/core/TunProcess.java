package dev.radminlan.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One local helper connects a user-owned Linux TUN to a CoreSession. */
final class TunProcess implements AutoCloseable {
    private static final byte[] HELLO = {'R', 'L', 'T', '1'};
    private final CoreSession session;
    private final Process process;
    private final ArrayBlockingQueue<byte[]> incoming = new ArrayBlockingQueue<>(128);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Consumer<byte[]> receiver = incoming::offer;
    private Thread writer;

    static TunProcess open(CoreSession session, Path helper, String name) throws IOException, InterruptedException {
        if (!System.getProperty("os.name", "").equalsIgnoreCase("Linux")) throw new IOException("This TUN helper requires Linux");
        if (!name.matches("[A-Za-z0-9_.-]{1,15}") || !Files.isRegularFile(helper) || !Files.isExecutable(helper)) {
            throw new IllegalArgumentException("Expected an existing TUN name and an executable helper file");
        }
        Process process = new ProcessBuilder(helper.toAbsolutePath().toString(), name)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        TunProcess bridge = new TunProcess(session, process);
        if (!bridge.start()) {
            bridge.close();
            throw new IOException("TUN helper did not attach to the interface");
        }
        return bridge;
    }

    private TunProcess(CoreSession session, Process process) {
        this.session = session;
        this.process = process;
    }

    private boolean start() throws InterruptedException, IOException {
        var started = new java.util.concurrent.CompletableFuture<Boolean>();
        Thread.ofVirtual().name("radminlan-tun-read").start(() -> readPackets(started));
        try { return started.get(5, TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException timeout) { return false; }
        catch (java.util.concurrent.ExecutionException failure) { throw new IOException("Unable to attach TUN", failure.getCause()); }
        finally { if (!started.isDone() || started.isCompletedExceptionally()) close(); }
    }

    private void readPackets(java.util.concurrent.CompletableFuture<Boolean> started) {
        try (var input = new DataInputStream(new BufferedInputStream(process.getInputStream()))) {
            if (!Arrays.equals(input.readNBytes(4), HELLO)) { started.complete(false); return; }
            synchronized (this) {
                if (closed.get()) { started.complete(false); return; }
                session.attachPacketReceiver(receiver);
                writer = Thread.ofVirtual().name("radminlan-tun-write").start(this::writePackets);
            }
            started.complete(true);
            while (!closed.get()) {
                int length = input.readUnsignedShort();
                if (length < 20 || length > Ipv4Link.MTU) return;
                byte[] packet = input.readNBytes(length);
                if (packet.length != length) return;
                session.sendIpv4(receiver, packet);
            }
        } catch (IOException | RuntimeException error) {
            started.completeExceptionally(error);
        } finally { close(); }
    }

    private void writePackets() {
        try (var output = new DataOutputStream(new BufferedOutputStream(process.getOutputStream()))) {
            while (!closed.get()) {
                byte[] packet = incoming.take();
                output.writeShort(packet.length);
                output.write(packet);
                output.flush();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // The local helper closed its packet channel.
        } finally { close(); }
    }

    boolean active() { return !closed.get(); }

    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        session.detachPacketReceiver(receiver);
        process.destroyForcibly();
        if (writer != null) writer.interrupt();
        incoming.clear();
    }
}
