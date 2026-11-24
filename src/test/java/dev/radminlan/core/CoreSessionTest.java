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
import java.io.IOException;
import java.net.SocketException;
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
    private static final long OWN_ID = 123, MEMBER_ID = 124;
    private static final String NETWORK = "Synthetic friends";
    private static final byte[] PASSWORD = "synthetic-control-secret".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GUID = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 };
    private static final String ID = HexFormat.of().formatHex(GUID);

    @Test void authenticatesAndRestoresNetworkChoicesAcrossSessions() throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
             var session = new CoreSession(profile, List.of(), ignored -> {})) {
            listener.setSoTimeout(8_000);
            int port = listener.getLocalPort();
            Files.writeString(profile.resolve("coordinator.properties"), "host=127.0.0.1\nport=" + port);
            Files.write(profile.resolve("modulus.bin"), SyntheticControlService.modulus());
            Path identityPath = CoordinatorEndpoint.load(profile, "127.0.0.1", port).identityDirectory();
            Files.createDirectories(identityPath);
            try (var identity = new Identity(OWN_ID, ipv4("26.0.0.1"), "Alex (playing with radminlan mod)", new byte[16], PASSWORD, "127.0.0.1")) {
                identity.save(identityPath.resolve("identity.json"));
            }
            byte[] network = tlv(0x1315, concat(tlv(0x0d000309, GUID), text(0x03000306, NETWORK), u32(0x0100030a, 1)));
            var served = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    try (var service = attach(listener, new byte[0], "Alex (playing with radminlan mod)")) {
                        var join = fields(field(fields(next(service, 39)), 0x131c));
                        long joinId = wide(join, 0x02000340);
                        // An unrelated acknowledgement must not complete the operation.
                        service.send(joinResult(joinId + 1, network));
                        service.send(joinResult(joinId, network));
                        service.send(concat(server(42), tlv(0x131f, tlv(0x131e,
                                concat(u64(0x020001e1, OWN_ID), tlv(0x0d000309, GUID))))));
                        waitForDisconnect(service);
                    }
                    byte[] member = concat(
                            tlv(0x1317, concat(u64(0x020001e1, MEMBER_ID), text(0x03000304, "Test member"), u32(0x01000305, 0x1a000002))),
                            tlv(0x1318, concat(tlv(0x0d000309, GUID), u64(0x020001e1, MEMBER_ID), u32(0x0100030a, 0))));
                    byte[] ownedNetwork = tlv(0x1315, concat(tlv(0x0d000309, GUID), text(0x03000306, NETWORK), u32(0x0100030a, 2)));
                    try (var service = attach(listener, concat(ownedNetwork, member), "Alex renamed")) {
                        confirmRename(service, "Alex renamed");
                        confirmMemberAction(service, 5, 1);
                        confirmMemberAction(service, 8, null);
                        confirmMemberAction(service, 9, null);
                        confirmMemberAction(service, 4, null);
                        var leave = fields(field(fields(next(service, 52)), 0x1319));
                        long leaveId = wide(leave, 0x02000340);
                        long sequence = number(leave, 0x0100034a);
                        service.send(concat(server(37), tlv(0x131a, concat(u64(0x02000340, leaveId),
                                u32(0x0100030c, 3), u32(0x0100034a, sequence), tlv(0x0d000309, GUID)))));
                        waitForDisconnect(service);
                    }
                } catch (Exception error) { throw new AssertionError(error); }
            });
            assertFalse(session.manage(Action.JOIN, NETWORK, null).join());
            session.setAdvertisedName("Alex (playing with radminlan mod)");
            assertTrue(session.start());
            awaitConnected(session, served);
            assertTrue(session.manage(Action.JOIN, NETWORK, null).get(5, TimeUnit.SECONDS));
            assertEquals(ID, session.view().networks().getFirst().id());
            assertTrue(session.view().networks().getFirst().enabled());
            assertTrue(session.manage(Action.DISABLE, ID, null).get(3, TimeUnit.SECONDS));
            assertFalse(session.view().networks().getFirst().enabled());
            assertFalse(session.removeMember(ID, MEMBER_ID).get(3, TimeUnit.SECONDS));
            session.close();
            assertTrue(session.awaitStopped(Duration.ofSeconds(4)));
            try (var restarted = new CoreSession(profile, List.of(NETWORK), ignored -> {})) {
                restarted.setAdvertisedName("Alex renamed");
                assertTrue(restarted.start());
                awaitConnected(restarted, served);
                assertFalse(restarted.view().networks().getFirst().enabled(), "saved disable wins over the startup network list");
                assertTrue(restarted.manage(Action.ENABLE, ID, null).get(3, TimeUnit.SECONDS));
                assertTrue(restarted.view().networks().getFirst().enabled());
                assertEquals(MEMBER_ID, restarted.view().networks().getFirst().peers().getFirst().id());
                assertFalse(restarted.removeMember(ID, OWN_ID).get(3, TimeUnit.SECONDS));
                assertTrue(restarted.manageMember(CoreSession.MemberAction.APPROVE, ID, MEMBER_ID).get(5, TimeUnit.SECONDS));
                assertEquals(1, restarted.view().networks().getFirst().peers().getFirst().role());
                assertTrue(restarted.manageMember(CoreSession.MemberAction.GRANT_ADMIN, ID, MEMBER_ID).get(5, TimeUnit.SECONDS));
                assertEquals(2, restarted.view().networks().getFirst().peers().getFirst().role());
                assertTrue(restarted.manageMember(CoreSession.MemberAction.REVOKE_ADMIN, ID, MEMBER_ID).get(5, TimeUnit.SECONDS));
                assertEquals(1, restarted.view().networks().getFirst().peers().getFirst().role());
                assertTrue(restarted.view().networks().getFirst().enabled());
                assertTrue(restarted.removeMember(ID, MEMBER_ID).get(5, TimeUnit.SECONDS));
                assertTrue(restarted.view().networks().getFirst().peers().isEmpty());
                assertTrue(restarted.manage(Action.LEAVE, ID, null).get(5, TimeUnit.SECONDS));
                assertTrue(restarted.view().networks().isEmpty());
                restarted.stop();
                assertTrue(restarted.awaitStopped(Duration.ofSeconds(4)));
            }
            served.get(4, TimeUnit.SECONDS);
        }
    }

    @Test void keepsTheDeviceAddressWhenTheLaunchersHomeDirectoryChanges() throws Exception {
        try (var listener = new ServerSocket(0, 1, ipv4("127.0.0.1"))) {
            listener.setSoTimeout(5_000);
            int port = listener.getLocalPort();
            Path instanceProfile = profile.resolve("game/config/radminlinker-core");
            Path oldHomeProfile = profile.resolve("old-home/.radminlan/core");
            Path newHomeProfile = profile.resolve("new-home/.radminlan/core");
            for (Path location : List.of(instanceProfile, oldHomeProfile)) {
                Files.createDirectories(location);
                Files.writeString(location.resolve("coordinator.properties"), "host=127.0.0.1\nport=" + port);
                Files.write(location.resolve("modulus.bin"), SyntheticControlService.modulus());
            }
            Path oldIdentity = CoordinatorEndpoint.load(oldHomeProfile, "127.0.0.1", port).identityDirectory();
            try (var identity = new Identity(OWN_ID, ipv4("26.0.0.1"), "Alex", new byte[16], PASSWORD, "127.0.0.1")) {
                identity.save(oldIdentity.resolve("identity.json"));
            }
            var served = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    for (int launch = 0; launch < 2; launch++) {
                        try (var service = attach(listener, new byte[0], "Alex")) {
                            waitForDisconnect(service);
                        }
                    }
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
            for (Path launcherHome : List.of(oldHomeProfile, newHomeProfile)) {
                try (var session = new CoreSession(instanceProfile, launcherHome, List.of(), ignored -> {})) {
                    assertTrue(session.start());
                    awaitConnected(session, served);
                    assertEquals("26.0.0.1", session.view().status().vip());
                    session.stop();
                    assertTrue(session.awaitStopped(Duration.ofSeconds(4)));
                }
            }
            served.get(4, TimeUnit.SECONDS);
            assertTrue(Files.exists(oldIdentity.resolve("identity.json")));
        }
    }

    @Test void changesAnExistingIdentityNameWhileConnected() throws Exception {
        try (var listener = new ServerSocket(0, 1, ipv4("127.0.0.1"));
             var session = new CoreSession(profile, List.of(), ignored -> {})) {
            listener.setSoTimeout(7_000);
            int port = listener.getLocalPort();
            Files.writeString(profile.resolve("coordinator.properties"), "host=127.0.0.1\nport=" + port);
            Files.write(profile.resolve("modulus.bin"), SyntheticControlService.modulus());
            Path directory = CoordinatorEndpoint.load(profile, "127.0.0.1", port).identityDirectory();
            Files.createDirectories(directory);
            try (var saved = new Identity(OWN_ID, ipv4("26.0.0.1"), "RadminLAN", new byte[16], PASSWORD, "127.0.0.1")) {
                saved.save(directory.resolve("identity.json"));
            }
            byte[] roster = concat(
                    tlv(0x1315, concat(tlv(0x0d000309, GUID), text(0x03000306, NETWORK), u32(0x0100030a, 2))),
                    tlv(0x1317, concat(u64(0x020001e1, OWN_ID), text(0x03000304, "RadminLAN"), u32(0x01000305, 0x1a000001))),
                    tlv(0x1318, concat(tlv(0x0d000309, GUID), u64(0x020001e1, OWN_ID), u32(0x0100030a, 2))));
            var renamed = new java.util.concurrent.CompletableFuture<Void>();
            var removedSuffix = new java.util.concurrent.CompletableFuture<Void>();
            var served = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    try (var service = attach(listener, roster, "RadminLAN")) {
                        confirmRename(service, "frank_banana （安卓，通过radmin linker模组）");
                        renamed.complete(null);
                        confirmRename(service, "frank_banana");
                        service.send(concat(server(41), tlv(0x131f, tlv(0x132d,
                                concat(u64(0x020001e1, OWN_ID), text(0x03000304, "frank_banana"))))));
                        removedSuffix.complete(null);
                        waitForDisconnect(service);
                    }
                } catch (Exception | AssertionError failure) {
                    renamed.completeExceptionally(failure);
                    removedSuffix.completeExceptionally(failure);
                    throw new AssertionError(failure);
                }
            });
            assertTrue(session.start());
            awaitConnected(session, served);
            var self = session.view().networks().getFirst().peers().getFirst();
            assertTrue(self.local());
            assertTrue(self.online());
            assertEquals("26.0.0.1", self.address());
            session.setAdvertisedName("frank_banana （安卓，通过radmin linker模组）");
            renamed.get(8, TimeUnit.SECONDS);
            session.setAdvertisedName("frank_banana");
            removedSuffix.get(8, TimeUnit.SECONDS);
            awaitConnected(session, served);
            assertEquals(ID, session.view().networks().getFirst().id());
            long until = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (!session.view().networks().getFirst().peers().getFirst().name().equals("frank_banana")
                    && System.nanoTime() < until) Thread.sleep(20);
            var renamedPeer = session.view().networks().getFirst().peers().getFirst();
            assertEquals("frank_banana", renamedPeer.name());
            assertEquals(OWN_ID, renamedPeer.id());
            assertEquals(self.address(), renamedPeer.address());
            assertTrue(renamedPeer.online());
            session.stop();
            assertTrue(session.awaitStopped(Duration.ofSeconds(4)));
            served.get(4, TimeUnit.SECONDS);
        }
    }

    private static void awaitConnected(CoreSession session, java.util.concurrent.CompletableFuture<Void> served)
            throws InterruptedException {
        long until = System.nanoTime() + Duration.ofSeconds(7).toNanos();
        while (!session.view().status().phase().equals("CONNECTED") && !served.isDone() && System.nanoTime() < until) {
            Thread.sleep(20);
        }
        if (served.isCompletedExceptionally()) served.join();
        assertEquals("CONNECTED", session.view().status().phase());
    }

    private static SyntheticControlService.Authenticated attach(ServerSocket listener, byte[] network, String alias) throws Exception {
        var service = SyntheticControlService.authenticate(listener.accept(), 4, OWN_ID, PASSWORD);
        try {
            var login = fields(service.receive());
            assertEquals(20, number(login, TlvCodec.CLIENT_OP));
            assertEquals(alias, TlvCodec.text(field(fields(field(login, 0x122f)), 0x03000304)));
            service.send(server(21));
            service.send(concat(server(38), tlv(0x1316, concat(u32(0x01000305, 0x1a000001),
                    tlv(0x09000305, new byte[16]), network))));
            service.stream().sustain();
            byte[] query = next(service, 43);
            long catalog = wide(fields(field(fields(query), 0x1332)), 0x02000340);
            service.send(concat(server(45), tlv(0x1334, concat(u64(0x02000340, catalog), u64(0x0200030f, 0)))));
            return service;
        } catch (Exception | AssertionError failure) {
            service.close();
            throw failure;
        }
    }

    private static void confirmMemberAction(SyntheticControlService.Authenticated service, int action, Integer role) throws Exception {
        var request = fields(field(fields(next(service, 52)), 0x1319));
        assertEquals(action, number(request, 0x0100030c));
        assertEquals(MEMBER_ID, wide(request, 0x020001e1));
        assertArrayEquals(GUID, field(request, 0x0d000309));
        service.send(concat(server(37), tlv(0x131a, concat(u32(0x0100030c, action),
                u64(0x02000340, wide(request, 0x02000340)), u32(0x0100034a, number(request, 0x0100034a)),
                tlv(0x0d000309, GUID), u64(0x020001e1, MEMBER_ID),
                role == null ? new byte[0] : u32(0x0100030a, role)))));
    }

    private static void confirmRename(SyntheticControlService.Authenticated service, String name) throws Exception {
        var request = fields(field(fields(next(service, 52)), 0x1319));
        assertEquals(10, number(request, 0x0100030c));
        assertEquals(name, TlvCodec.text(field(request, 0x03000304)));
        service.send(concat(server(37), tlv(0x131a, concat(u32(0x0100030c, 10),
                u64(0x02000340, wide(request, 0x02000340)), u32(0x0100034a, number(request, 0x0100034a))))));
    }

    private static void waitForDisconnect(SyntheticControlService.Authenticated service) {
        try {
            while (true) assertEquals(4, number(fields(service.receive()), TlvCodec.CLIENT_OP));
        } catch (EOFException | SocketException disconnected) {
            // Some TCP stacks reset a socket when its owner closes with unread input.
        } catch (IOException unexpected) {
            fail("Expected the client to close its control connection", unexpected);
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
