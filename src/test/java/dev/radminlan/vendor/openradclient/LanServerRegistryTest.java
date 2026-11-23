package dev.radminlan.vendor.openradclient;

import dev.radminlan.vendor.openradplugin.discovery.LanAdvertisement;
import dev.radminlan.vendor.openradplugin.link.OpenRadAddress;
import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

final class LanServerRegistryTest {
    @Test void authenticatedRoomRefreshesAndExpires() throws Exception {
        var clock = new AtomicLong(1_000);
        var rooms = new LanServerRegistry(clock::get);
        var peer = (Inet4Address) InetAddress.getByAddress(new byte[]{26, 11, 12, 13});
        var unrelated = (Inet4Address) InetAddress.getByAddress(new byte[]{26, 11, 12, 14});
        var mac = OpenRadAddress.mac(0x1a0b0c0d);
        var announcement = new LanAdvertisement("VPN fixture", 25565);
        var frame = announcement.frame(peer, 25565, 1);
        assertFalse(rooms.accept(unrelated, mac, frame));
        assertTrue(rooms.snapshot().isEmpty());
        assertTrue(rooms.accept(peer, mac, frame));
        assertEquals("26.11.12.13", rooms.snapshot().getFirst().address());
        assertEquals("VPN fixture", rooms.snapshot().getFirst().motd());
        assertEquals(25565, rooms.snapshot().getFirst().port());
        clock.addAndGet(8_000);
        assertTrue(rooms.accept(peer, mac, announcement.frame(peer, 25565, 2)));
        clock.addAndGet(9_999);
        assertEquals(1, rooms.snapshot().size());
        clock.incrementAndGet();
        assertTrue(rooms.snapshot().isEmpty());
        assertTrue(rooms.accept(peer, mac, frame));
        rooms.removePeer(peer);
        assertTrue(rooms.snapshot().isEmpty());
    }
}
