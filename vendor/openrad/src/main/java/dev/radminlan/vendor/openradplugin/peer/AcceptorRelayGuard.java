package dev.radminlan.vendor.openradplugin.peer;

import static dev.radminlan.vendor.openradplugin.protocol.Wire.fields;
import static dev.radminlan.vendor.openradplugin.protocol.Wire.number;

import dev.radminlan.vendor.openradplugin.protocol.TlvCodec;

import java.io.IOException;
import java.time.Duration;
import java.util.function.LongSupplier;

/** Owned by one runtime's control worker. Relay policy survives automatic reattachment. */
public final class AcceptorRelayGuard {
    private static final long OBJECTION_WINDOW = Duration.ofSeconds(5).toNanos();

    @FunctionalInterface
    public interface Sender {
        void send(byte[] record) throws IOException;
    }

    private final LongSupplier clock;
    private Long lastRequest;
    private boolean disabled;

    public AcceptorRelayGuard() {
        this(System::nanoTime);
    }

    AcceptorRelayGuard(LongSupplier clock) {
        this.clock = clock;
    }

    public void startAttachment() {
        lastRequest = null;
    }

    /** Correlate objections with requests written by this attachment, excluding queued records. */
    public void send(byte[] record, Sender sender) throws IOException {
        boolean relay = number(fields(record), TlvCodec.CLIENT_OP) == 22;
        if (relay && disabled) return;
        sender.send(record);
        if (relay) lastRequest = clock.getAsLong();
    }

    public boolean serverObjected() {
        if (disabled || lastRequest == null || clock.getAsLong() - lastRequest > OBJECTION_WINDOW)
            return false;
        disabled = true;
        return true;
    }
}
