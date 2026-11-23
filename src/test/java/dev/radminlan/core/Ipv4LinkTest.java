package dev.radminlan.core;

import dev.radminlan.vendor.openradplugin.link.Checksum;
import dev.radminlan.vendor.openradplugin.link.EthernetFrame;
import dev.radminlan.vendor.openradplugin.link.OpenRadAddress;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class Ipv4LinkTest {
    @Test void acceptsOnlyPacketsBoundToTheAuthenticatedPeerAndLocalAddress() {
        var local = CoreSession.virtualAddress("26.20.30.40");
        var peer = CoreSession.virtualAddress("26.20.30.41");
        byte[] mac = OpenRadAddress.mac(0x1a141e29);
        byte[] udp = packet(0x1a141e28, 0x1a141e29);
        assertEquals(peer, Ipv4Link.destination(udp, local));
        var outbound = EthernetFrame.parse(Ipv4Link.outbound(udp, local, mac));
        assertArrayEquals(mac, outbound.destinationMac());
        assertArrayEquals(udp, outbound.payload());
        byte[] response = packet(0x1a141e29, 0x1a141e28);
        byte[] localMac = outbound.sourceMac();
        assertArrayEquals(response, Ipv4Link.inbound(frame(localMac, mac, response), peer, mac, local));
        assertNull(Ipv4Link.inbound(frame(localMac, new byte[6], response), peer, mac, local));
        assertNull(Ipv4Link.inbound(frame(localMac, mac, packet(0x1a141e29, 0x1a141e30)), peer, mac, local));
        assertNull(Ipv4Link.inbound(frame(localMac, mac, packet(0x1a141e27, 0x1a141e28)), peer, mac, local));
        assertNull(Ipv4Link.destination(packet(0x1a141e27, 0x1a141e29), local));
        assertNull(Ipv4Link.destination(packet(0x1a141e28, 0x7f000001), local));
        udp[19] ^= 1;
        assertNull(Ipv4Link.destination(udp, local));
    }

    private static byte[] packet(int from, int to) {
        byte[] packet = new byte[28];
        packet[0] = 0x45;
        packet[8] = 64;
        packet[9] = 17;
        Checksum.put16(packet, 2, packet.length);
        Checksum.put32(packet, 12, from);
        Checksum.put32(packet, 16, to);
        Checksum.put16(packet, 10, Checksum.onesComplement(packet, 0, 20));
        return packet;
    }

    private static byte[] frame(byte[] target, byte[] sender, byte[] packet) {
        return new EthernetFrame(target, sender, EthernetFrame.IPV4, packet).encode();
    }
}
