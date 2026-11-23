package dev.radminlan.core;

import dev.radminlan.vendor.openradplugin.link.Checksum;
import dev.radminlan.vendor.openradplugin.link.EthernetFrame;
import dev.radminlan.vendor.openradplugin.link.OpenRadAddress;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/** Converts individual IPv4 packets between a system TUN and an authenticated peer link. */
public final class Ipv4Link {
    public static final int MTU = 1500;

    private Ipv4Link() {}

    public static Inet4Address destination(byte[] packet, Inet4Address local) {
        if (!valid(packet) || Checksum.u32(packet, 12) != address(local) || packet[16] != 26) return null;
        try { return (Inet4Address) InetAddress.getByAddress(Arrays.copyOfRange(packet, 16, 20)); }
        catch (UnknownHostException impossible) { throw new AssertionError(impossible); }
    }

    public static byte[] outbound(byte[] packet, Inet4Address local, byte[] peerMac) {
        return new EthernetFrame(peerMac, OpenRadAddress.mac(address(local)), EthernetFrame.IPV4, packet).encode();
    }

    public static byte[] inbound(byte[] frame, Inet4Address peer, byte[] peerMac, Inet4Address local) {
        try {
            var ethernet = EthernetFrame.parse(frame);
            int localIp = address(local);
            if (ethernet.etherType() != EthernetFrame.IPV4 || !Arrays.equals(ethernet.sourceMac(), peerMac)
                    || !Arrays.equals(ethernet.destinationMac(), OpenRadAddress.mac(localIp))) return null;
            byte[] packet = ethernet.payload();
            if (!valid(packet) || Checksum.u32(packet, 12) != address(peer)
                    || Checksum.u32(packet, 16) != localIp) return null;
            return packet;
        } catch (IllegalArgumentException malformed) { return null; }
    }

    private static int address(Inet4Address ip) { return Checksum.u32(ip.getAddress(), 0); }

    private static boolean valid(byte[] packet) {
        if (packet == null || packet.length < 20 || packet.length > MTU) return false;
        int headerLength = (packet[0] & 15) * 4;
        return (packet[0] & 0xf0) == 0x40 && headerLength >= 20 && headerLength <= packet.length
                && Checksum.u16(packet, 2) == packet.length && packet[8] != 0
                && Checksum.onesComplement(packet, 0, headerLength) == 0;
    }
}
