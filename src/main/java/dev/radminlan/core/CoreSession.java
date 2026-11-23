package dev.radminlan.core;

import dev.radminlan.vendor.openradclient.ClientConfig;
import dev.radminlan.vendor.openradclient.ClientVpnRuntime;
import dev.radminlan.vendor.openradclient.ClientVpnRuntime.Action;
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
    private final ClientVpnRuntime runtime;

    public CoreSession(Path profile, List<String> initialNetworks, Consumer<String> logger) {
        runtime = new ClientVpnRuntime(new ClientConfig(false, initialNetworks), profile, logger);
    }

    public boolean start() { return runtime.connect(); }
    public void stop() { runtime.disconnect(); }
    public boolean awaitStopped(Duration timeout) throws InterruptedException { return runtime.awaitStopped(timeout); }
    public ManagementView view() { return runtime.managementView(); }
    public List<String> publicNetworks() {
        return runtime.minecraftCatalog().entries().stream().map(entry -> entry.name()).distinct().sorted().toList();
    }
    public boolean catalogReady() { return runtime.minecraftCatalog().state().startsWith("READY"); }
    public CompletableFuture<Boolean> manage(Action action, String target, char[] password) {
        return runtime.manage(action, target, password);
    }

    public OutboundTcpStack.Connection openTcp(Inet4Address peer, int port) throws IOException {
        if (peer == null || peer.getAddress()[0] != 26 || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Expected a virtual IPv4 address and port");
        }
        return runtime.openTcp(peer, port, Duration.ofSeconds(20));
    }

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
