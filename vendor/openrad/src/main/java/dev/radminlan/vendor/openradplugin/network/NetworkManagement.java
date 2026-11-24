package dev.radminlan.vendor.openradplugin.network;

import static dev.radminlan.vendor.openradplugin.protocol.Wire.*;

import dev.radminlan.vendor.openradplugin.crypto.Crypto;
import dev.radminlan.vendor.openradplugin.protocol.TlvCodec;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Correlated requests for network management and changes to the authenticated device name.
 */
public final class NetworkManagement implements AutoCloseable {
    private static final int RENAME_DEVICE = 10;
    private final int action, sequence;
    private final long id, expectedMember;
    private final String expectedName, expectedNetwork;
    private final byte[] request;
    private boolean started, closed, complete;

    private NetworkManagement(
            int action,
            long id,
            int sequence,
            String expectedName,
            String expectedNetwork,
            long expectedMember,
            byte[] body) {
        require(id != 0 && sequence > 0, "invalid network request correlation");
        this.action = action;
        this.id = id;
        this.sequence = sequence;
        this.expectedName = expectedName;
        this.expectedNetwork = expectedNetwork;
        this.expectedMember = expectedMember;
        request =
                concat(
                        op(52),
                        tlv(
                                0x1319,
                                concat(
                                        u32(0x0100030c, action),
                                        u64(0x02000340, id),
                                        u32(0x0100034a, sequence),
                                        body)));
    }

    /** Consumes the supplied password array. The raw password is never put in a request. */
    public static NetworkManagement createPrivate(
            String name, char[] password, long id, int sequence) {
        return createPrivate(name, password, id, sequence, Crypto.random(32));
    }

    static NetworkManagement createPrivate(
            String name, char[] password, long id, int sequence, byte[] salt) {
        byte[] wirePassword = null, verifier = null;
        try {
            require(
                    name != null && !name.isBlank() && name.length() <= 255,
                    "invalid network name");
            text(0x03000306, name);
            require(
                    password != null && password.length >= 6 && password.length <= 256,
                    "invalid network password length");
            for (char c : password) require(!Character.isISOControl(c), "invalid network password");
            wirePassword = (new String(password) + "\0").getBytes(StandardCharsets.UTF_8);
            verifier =
                    Crypto.networkVerifier(NetworkJoin.networkIdentity(name), wirePassword, salt);
            return new NetworkManagement(
                    1,
                    id,
                    sequence,
                    name,
                    null,
                    0,
                    tlv(
                            0x1314,
                            concat(
                                    u32(0x0b000308, 0),
                                    text(0x03000306, name),
                                    tlv(0x0a000307, verifier))));
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (wirePassword != null) Arrays.fill(wirePassword, (byte) 0);
            if (verifier != null) Arrays.fill(verifier, (byte) 0);
        }
    }

    public static NetworkManagement leave(String networkId, long id, int sequence) {
        return new NetworkManagement(3, id, sequence, null, networkId, 0, network(networkId));
    }

    public static NetworkManagement delete(String networkId, long id, int sequence) {
        return new NetworkManagement(7, id, sequence, null, networkId, 0, network(networkId));
    }

    public static NetworkManagement removeMember(String networkId, long member, long id, int sequence) {
        require(member != 0, "invalid member ID");
        return new NetworkManagement(4, id, sequence, null, networkId, member,
                concat(network(networkId), u64(0x020001e1, member)));
    }

    public static NetworkManagement renameDevice(String name, long id, int sequence) {
        return new NetworkManagement(RENAME_DEVICE, id, sequence, null, null, 0, text(0x03000304, name));
    }

    public static NetworkManagement changeMemberRole(String networkId, long member, int roleAction,
                                                       long id, int sequence) {
        require(member != 0 && (roleAction == 5 || roleAction == 8 || roleAction == 9), "invalid member action");
        return new NetworkManagement(roleAction, id, sequence, null, networkId, member,
                concat(network(networkId), u64(0x020001e1, member)));
    }

    private static byte[] network(String networkId) {
        byte[] bytes;
        try {
            bytes = HexFormat.of().parseHex(networkId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid network ID");
        }
        require(bytes.length == 16 && !Arrays.equals(bytes, new byte[16]), "invalid network ID");
        return tlv(0x0d000309, bytes);
    }

    public byte[] start() {
        require(!started && !closed, "network operation already started or closed");
        started = true;
        return request.clone();
    }

    /**
     * Returns null for unrelated messages, and a result only for the matching operation 37
     * response.
     */
    public Result handle(byte[] data, Membership membership) {
        require(started && !closed && !complete, "network operation not active");
        if (TlvCodec.operation(data) != 37) return null;
        var response = fields(field(fields(data), 0x131a));
        if (wide(response, 0x02000340) != id) return null;
        require(number(response, 0x0100030c) == action, "network response action mismatch");
        require(number(response, 0x0100034a) == sequence, "network response context mismatch");
        var error = TlvCodec.optional(response, 0x010001d2);
        if (error.isPresent() && TlvCodec.int32(error.orElseThrow()) != 0) {
            complete = true;
            return new Result(false, TlvCodec.int32(error.orElseThrow()), expectedNetwork);
        }
        String networkId;
        if (action == RENAME_DEVICE) {
            networkId = null;
        } else if (action == 1) {
            byte[] root = tlv(0x1316, tlv(0x1315, field(response, 0x1315)));
            Membership returned = new Membership(0);
            returned.snapshot(root);
            var matching =
                    returned.networks().values().stream()
                            .filter(n -> n.name().equals(expectedName))
                            .findFirst();
            require(matching.isPresent(), "create response network name mismatch");
            networkId = matching.orElseThrow().id();
            membership.snapshot(root);
        } else {
            networkId = guid(field(response, 0x0d000309));
            require(networkId.equalsIgnoreCase(expectedNetwork), "network response ID mismatch");
            if (expectedMember != 0) {
                require(wide(response, 0x020001e1) == expectedMember, "network response member mismatch");
                switch (action) {
                    case 4 -> membership.removeMember(networkId, expectedMember);
                    case 5 -> membership.setRole(networkId, expectedMember,
                            TlvCodec.optional(response, 0x0100030a).map(TlvCodec::int32).orElse(1L).intValue());
                    case 8 -> membership.setRole(networkId, expectedMember, 2);
                    case 9 -> membership.setRole(networkId, expectedMember, 1);
                    default -> throw new IllegalArgumentException("unexpected member action");
                }
            } else {
                membership.removeNetwork(networkId);
            }
        }
        complete = true;
        return new Result(true, 0, networkId);
    }

    public boolean complete() {
        return complete;
    }

    public record Result(boolean success, long errorCode, String networkId) {}

    @Override
    public void close() {
        Arrays.fill(request, (byte) 0);
        closed = true;
    }

    @Override
    public String toString() {
        return "NetworkManagement[action=" + action + ", redacted]";
    }
}
