package dev.radminlan.vendor.openradplugin.peer;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static dev.radminlan.vendor.openradplugin.peer.PeerChannel.Path.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class PeerChannelAddressTest {
    @Test void showsOnlyTheNumericAddressOfAResolvedDirectEndpoint() throws Exception {
        var ipv4 = InetAddress.getByAddress(new byte[] {(byte) 192, 0, 2, 1});
        var ipv6 = InetAddress.getByName("2001:db8::1");
        var tcp = new InetSocketAddress(ipv4, 48000);
        var udp = new InetSocketAddress(ipv6, 48001);
        assertEquals("192.0.2.1", PeerChannel.directAddress(DIRECT_TCP, tcp));
        assertEquals(ipv6.getHostAddress(), PeerChannel.directAddress(DIRECT_UDP, udp));
        assertEquals("", PeerChannel.directAddress(RELAY, tcp));
        assertEquals("", PeerChannel.directAddress(RELAY, udp));
        assertEquals("", PeerChannel.directAddress(DIRECT_TCP, null));
        assertEquals("", PeerChannel.directAddress(DIRECT_UDP, InetSocketAddress.createUnresolved("example.test", 1234)));
    }
}
