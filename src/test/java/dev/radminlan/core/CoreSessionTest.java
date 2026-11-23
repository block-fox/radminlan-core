package dev.radminlan.core;

import dev.radminlan.core.CoreSession.Action;
import dev.radminlan.vendor.openradplugin.crypto.Identity;
import dev.radminlan.vendor.openradplugin.protocol.TlvCodec;
import dev.radminlan.vendor.openradplugin.session.CoordinatorEndpoint;
import dev.radminlan.vendor.openradplugin.session.SyntheticControlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.EOFException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static dev.radminlan.vendor.openradplugin.protocol.Wire.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class CoreSessionTest {
    @TempDir Path profile;
    private static final long OWN_ID = 123;
    private static final String NETWORK = "Synthetic friends";
    private static final byte[] PASSWORD = "synthetic-control-secret".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GUID = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 };
    private static final String ID = HexFormat.of().formatHex(GUID);

    @Test void authenticateJoinAndLeaveOnTheSameControlConnection() throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
             var session = new CoreSession(profile, List.of(), ignored -> {})) {
            listener.setSoTimeout(8_000);
            int port = listener.getLocalPort();
            Files.writeString(profile.resolve("coordinator.properties"), "host=127.0.0.1\nport=" + port);
            Files.write(profile.resolve("modulus.bin"), SyntheticControlService.modulus());
            Path identityPath = CoordinatorEndpoint.load(profile, "127.0.0.1", port).identityDirectory();
            Files.createDirectories(identityPath);
            try (var identity = new Identity(OWN_ID, ipv4("26.0.0.1"), "Test", new byte[16], PASSWORD, "127.0.0.1")) {
                identity.save(identityPath.resolve("identity.json"));
            }
            var served = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try (var service = SyntheticControlService.authenticate(listener.accept(), 4, OWN_ID, PASSWORD)) {
                    assertEquals(20, number(fields(service.receive()), TlvCodec.CLIENT_OP));
                    service.send(server(21));
                    service.send(concat(server(38), tlv(0x1316, concat(u32(0x01000305, 0x1a000001),
                            tlv(0x09000305, new byte[16])))));
                    service.stream().sustain();
                    byte[] query = next(service, 43);
                    long catalog = wide(fields(field(fields(query), 0x1332)), 0x02000340);
                    service.send(concat(server(45), tlv(0x1334, concat(u64(0x02000340, catalog), u64(0x0200030f, 0)))));
                    byte[] request = next(service, 39);
                    var join = fields(field(fields(request), 0x131c));
                    long joinId = wide(join, 0x02000340);
                    byte[] network = tlv(0x1315, concat(tlv(0x0d000309, GUID), text(0x03000306, NETWORK), u32(0x0100030a, 1)));
                    // An unrelated acknowledgement must not complete the operation.
                    service.send(joinResult(joinId + 1, network));
                    service.send(joinResult(joinId, network));
                    service.send(concat(server(42), tlv(0x131f, tlv(0x131e,
                            concat(u64(0x020001e1, OWN_ID), tlv(0x0d000309, GUID))))));
                    var leave = fields(field(fields(next(service, 52)), 0x1319));
                    long leaveId = wide(leave, 0x02000340);
                    long sequence = number(leave, 0x0100034a);
                    service.send(concat(server(37), tlv(0x131a, concat(u64(0x02000340, leaveId),
                            u32(0x0100030c, 3), u32(0x0100034a, sequence), tlv(0x0d000309, GUID)))));
                    while (true) service.receive();
                } catch (EOFException closed) {
                    // The client closed the attachment after applying the confirmed leave.
                } catch (Exception error) { throw new AssertionError(error); }
            });
            assertFalse(session.manage(Action.JOIN, NETWORK, null).join());
            assertTrue(session.start());
            long until = System.nanoTime() + Duration.ofSeconds(7).toNanos();
            while (!session.view().status().phase().equals("CONNECTED") && !served.isDone() && System.nanoTime() < until) {
                Thread.sleep(20);
            }
            if (served.isCompletedExceptionally()) served.join();
            assertEquals("CONNECTED", session.view().status().phase());
            assertTrue(session.manage(Action.JOIN, NETWORK, null).get(5, TimeUnit.SECONDS));
            assertEquals(ID, session.view().networks().getFirst().id());
            assertTrue(session.view().networks().getFirst().enabled());
            assertTrue(session.manage(Action.DISABLE, ID, null).get(3, TimeUnit.SECONDS));
            assertFalse(session.view().networks().getFirst().enabled());
            assertTrue(session.manage(Action.LEAVE, ID, null).get(5, TimeUnit.SECONDS));
            assertTrue(session.view().networks().isEmpty());
            session.stop();
            assertTrue(session.awaitStopped(Duration.ofSeconds(4)));
            served.get(4, TimeUnit.SECONDS);
        }
    }

    private static byte[] next(SyntheticControlService.Authenticated service, int operation) throws Exception {
        while (true) {
            byte[] record = service.receive();
            long actual = number(fields(record), TlvCodec.CLIENT_OP);
            if (actual == operation) return record;
            assertEquals(4, actual);
        }
    }

    private static byte[] server(int op) { return u32(TlvCodec.SERVER_OP, op); }
    private static byte[] joinResult(long id, byte[] network) {
        return concat(server(37), tlv(0x131a,
                concat(u64(0x02000340, id), u32(0x0100030c, 2), tlv(0x1316, network))));
    }

    @Test void forwardsBothDirectionsAndClosesTheLocalLease() throws Exception {
        try (var endpoint = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
             var forwarder = new TcpForwarder((peer, port) -> {
                 assertEquals("26.1.2.3", peer.getHostAddress());
                 assertEquals(25565, port);
                 Socket transport = new Socket(InetAddress.getLoopbackAddress(), endpoint.getLocalPort());
                 var input = transport.getInputStream();
                 var output = transport.getOutputStream();
                 return new TcpForwarder.StreamPair() {
                     public java.io.InputStream input() { return input; }
                     public java.io.OutputStream output() { return output; }
                     public void shutdownOutput() throws java.io.IOException { transport.shutdownOutput(); }
                     public void close() { try { transport.close(); } catch (java.io.IOException ignored) { } }
                 };
             });
             var lease = forwarder.prepare(CoreSession.virtualAddress("26.1.2.3"), 25565);
             var client = new Socket()) {
            assertTrue(lease.endpoint().getAddress().isLoopbackAddress());
            endpoint.setSoTimeout(5_000);
            client.connect(lease.endpoint());
            client.setSoTimeout(5_000);
            try (var peer = endpoint.accept()) {
                peer.setSoTimeout(5_000);
                client.getOutputStream().write(43);
                client.shutdownOutput();
                assertArrayEquals(new byte[]{43}, peer.getInputStream().readAllBytes());
                peer.getOutputStream().write(81);
                peer.shutdownOutput();
                assertArrayEquals(new byte[]{81}, client.getInputStream().readAllBytes());
            }
        }
    }
}
