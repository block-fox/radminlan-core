package dev.radminlan.core;

import dev.radminlan.vendor.openradclient.ClientConfig;
import dev.radminlan.vendor.openradclient.ClientVpnRuntime;
import dev.radminlan.vendor.openradclient.ClientVpnRuntime.ManagementView;
import dev.radminlan.vendor.openradclient.virtual.OutboundTcpStack;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** A single, explicitly started VPN identity shared by the CLI and game adapters. */
public final class CoreSession implements AutoCloseable {
    public enum Action { JOIN, CREATE, LEAVE, DELETE, ENABLE, DISABLE }
    public enum MemberAction { APPROVE, GRANT_ADMIN, REVOKE_ADMIN }

    private final ClientVpnRuntime runtime;

    public CoreSession(Path profile, List<String> initialNetworks, Consumer<String> logger) {
        this(profile, null, initialNetworks, logger);
    }

    /** Import an existing device identity into a stable profile on its first use. */
    public CoreSession(Path profile, Path legacyProfile, List<String> initialNetworks, Consumer<String> logger) {
        runtime = new ClientVpnRuntime(new ClientConfig(false, initialNetworks), profile, legacyProfile, logger);
    }

    public boolean start() { return runtime.connect(); }
    public void stop() { runtime.disconnect(); }
    public boolean awaitStopped(Duration timeout) throws InterruptedException { return runtime.awaitStopped(timeout); }
    public record LanRoom(String motd, String host, int port) {}

    public List<LanRoom> lanRooms() {
        return runtime.lanServers().stream().map(room -> new LanRoom(room.motd(), room.address(), room.port())).toList();
    }

    public void setAdvertisedName(String name) { runtime.setAdvertisedName(name); }

    /** Advertise and forward the explicitly published game port. A zero port clears hosting. */
    public void setPublishedRoom(int port, String motd) { runtime.setPublishedRoom(port, motd); }

    public ManagementView view() { return runtime.managementView(); }
    public List<String> publicNetworks() {
        return runtime.minecraftCatalog().entries().stream().map(entry -> entry.name()).distinct().sorted().toList();
    }
    public boolean catalogReady() { return runtime.minecraftCatalog().state().startsWith("READY"); }
    public CompletableFuture<Boolean> manage(Action action, String target, char[] password) {
        return runtime.manage(ClientVpnRuntime.Action.valueOf(action.name()), target, password);
    }

    public CompletableFuture<Boolean> removeMember(String networkId, long memberId) {
        return runtime.removeMember(networkId, memberId);
    }

    public CompletableFuture<Boolean> manageMember(MemberAction action, String networkId, long memberId) {
        if (action == null) return CompletableFuture.completedFuture(false);
        return runtime.manageMember(ClientVpnRuntime.Action.valueOf(action.name()), networkId, memberId);
    }

    /** An address observed on a current, authenticated direct peer channel. */
    public String directPeerAddress(long rid) { return runtime.directPeerAddress(rid); }

    /** Measures RTT over an authenticated peer channel. A negative result means no reply. */
    public CompletableFuture<Long> pingPeer(long rid) { return runtime.pingPeer(rid); }

    public OutboundTcpStack.Connection openTcp(Inet4Address peer, int port) throws IOException {
        if (peer == null || peer.getAddress()[0] != 26 || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Expected a virtual IPv4 address and port");
        }
        return runtime.openTcp(peer, port, Duration.ofSeconds(20));
    }

    void attachPacketReceiver(Consumer<byte[]> receiver) { runtime.attachPacketReceiver(receiver); }
    void detachPacketReceiver(Consumer<byte[]> receiver) { runtime.detachPacketReceiver(receiver); }
    boolean sendIpv4(Consumer<byte[]> receiver, byte[] packet) { return runtime.sendIpv4(receiver, packet); }

    public static Path defaultProfile() {
        return Path.of(System.getProperty("user.home"), ".radminlan", "core");
    }

    public static Inet4Address virtualAddress(String text) {
        if (text == null || !text.startsWith("26.")) return null;
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] address = new byte[4];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(c -> c >= '0' && c <= '9')) return null;
            int number = Integer.parseInt(part);
            if (number > 255) return null;
            address[i] = (byte) number;
        }
        try { return (Inet4Address) InetAddress.getByAddress(address); }
        catch (UnknownHostException impossible) { throw new AssertionError(impossible); }
    }

    @Override public void close() { runtime.close(); }
}
