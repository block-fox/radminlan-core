package dev.radminlan.vendor.openradclient;

import static dev.radminlan.vendor.openradplugin.protocol.Wire.*;

import dev.radminlan.vendor.openradplugin.crypto.Identity;
import dev.radminlan.vendor.openradplugin.network.Membership;
import dev.radminlan.vendor.openradplugin.network.NetworkJoin;
import dev.radminlan.vendor.openradplugin.network.NetworkManagement;
import dev.radminlan.vendor.openradplugin.network.MinecraftNetworks;
import dev.radminlan.vendor.openradplugin.protocol.TlvCodec;
import dev.radminlan.vendor.openradplugin.session.*;
import dev.radminlan.vendor.openradplugin.peer.AcceptorRelayGuard;
import dev.radminlan.vendor.openradclient.virtual.OutboundTcpStack;

import java.io.*;
import java.net.Inet4Address;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * Client-only OpenRad attachment and authorized outbound game tunnels, without a system adapter.
 * Registration, attachment, membership and heartbeat processing belong to one daemon worker.
 */
public final class ClientVpnRuntime implements AutoCloseable {
    public record LanServer(String motd, String address, int port, long lastSeenMillis) {}
    public record CatalogSnapshot(String state, List<MinecraftNetworks.Entry> entries,
                                  Set<String> selectedNames, List<String> problems) {
        public CatalogSnapshot {
            entries = List.copyOf(entries);
            selectedNames = Set.copyOf(selectedNames);
            problems = List.copyOf(problems);
        }
    }
    public record Snapshot(String phase, String vip, List<String> networks,
                           int peers, String detail) {
        public Snapshot {
            networks = List.copyOf(networks);
        }
    }
    public record ManagedPeer(long id, String name, String address, Integer role, boolean connected, long latencyMs) {}
    public record ManagedNetwork(String id, String name, Integer role, boolean enabled, List<ManagedPeer> peers) {
        public ManagedNetwork { peers = List.copyOf(peers); }
    }
    public record ManagementView(Snapshot status, List<ManagedNetwork> networks) {
        public ManagementView { networks = List.copyOf(networks); }
    }
    public enum Action { JOIN, CREATE, LEAVE, DELETE, ENABLE, DISABLE, KICK }
    private record Request(Action action, String target, char[] password, long memberId, CompletableFuture<Boolean> result) {
        void finish(boolean ok) {
            if (password != null) Arrays.fill(password, '\0');
            result.complete(ok);
        }
    }
    private final ArrayBlockingQueue<Request> management = new ArrayBlockingQueue<>(16);
    private volatile ManagementView published;
    private final Map<String, Boolean> networkOverrides = new HashMap<>();
    private Path networkChoicesFile;
    private final ClientConfig config;
    private final Path dataDirectory;
    private final Consumer<String> logger;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean shutdown = new AtomicBoolean();
    /** Set while Provisioner may hold registration.pending; disconnect must not tear it down. */
    private final AtomicBoolean provisioning = new AtomicBoolean();
    private final AtomicReference<ControlSession> active = new AtomicReference<>();
    private volatile Snapshot state = new Snapshot("OFFLINE", "", List.of(), 0, "");
    private volatile Thread thread;
    private volatile ClientPeerService peers;
    private volatile Consumer<byte[]> packetReceiver;
    private volatile Set<String> activeNetworkNames = Set.of();
    private volatile CatalogSnapshot catalog = new CatalogSnapshot("OFFLINE", List.of(), Set.of(), List.of());
    private final AtomicLong lastIncomingRejectLog = new AtomicLong();
    /** Closed peer generations whose workers outlived PEER_STOP_WAIT; they still own the identity. */
    private final List<ClientPeerService> stragglers = new CopyOnWriteArrayList<>();
    /** What the attachment was doing; reported with a service disconnect to explain it. */
    private volatile String controlStage = "authenticating";

    public ClientVpnRuntime(ClientConfig config, Path dataDirectory, Consumer<String> logger) {
        this.config = Objects.requireNonNull(config);
        this.dataDirectory = Objects.requireNonNull(dataDirectory);
        this.logger = Objects.requireNonNull(logger);
    }

    public Snapshot status() { return state; }

    public ManagementView managementView() {
        ManagementView view = published;
        Snapshot status = state;
        return view != null && view.status() == status ? view : new ManagementView(status, List.of());
    }

    public CompletableFuture<Boolean> manage(Action action, String target, char[] password) {
        return enqueue(action, target, password, 0);
    }

    public CompletableFuture<Boolean> removeMember(String networkId, long memberId) {
        return enqueue(Action.KICK, networkId, null, memberId);
    }

    private synchronized CompletableFuture<Boolean> enqueue(Action action, String target, char[] password, long memberId) {
        var result = new CompletableFuture<Boolean>();
        if (stopped.get() || shutdown.get() || !state.phase().equals("CONNECTED") || peers == null || action == null || target == null
                || target.isBlank() || target.length() > 255 || target.chars().anyMatch(Character::isISOControl)
                || password != null && password.length > 256 || action == Action.KICK && memberId == 0) {
            result.complete(false);
            return result;
        }
        var request = new Request(action, target, password == null ? null : password.clone(), memberId, result);
        if (!management.offer(request)) request.finish(false);
        return result;
    }

    private synchronized void rejectPending() {
        Request next;
        while ((next = management.poll()) != null) next.finish(false);
    }
    public CatalogSnapshot minecraftCatalog() { return catalog; }

    public List<LanServer> lanServers() {
        if (stopped.get() || shutdown.get()) return List.of();
        ClientPeerService current = peers;
        return current == null ? List.of() : current.lanServers();
    }

    /** Blocking API: invoke only on a networking worker, never Minecraft's render thread. */
    public OutboundTcpStack.Connection openTcp(Inet4Address destination, int port, Duration timeout)
            throws IOException {
        ClientPeerService current = peers;
        if (current == null || stopped.get()) throw new IOException("OpenRad is not connected");
        if (packetReceiver != null) throw new IOException("The system TUN owns this session's packets");
        return current.openTcp(destination, port, timeout);
    }

    public synchronized void attachPacketReceiver(Consumer<byte[]> receiver) {
        Objects.requireNonNull(receiver);
        if (packetReceiver != null || peers == null || !state.phase().equals("CONNECTED"))
            throw new IllegalStateException("The connected session needs an unused packet receiver");
        packetReceiver = receiver;
    }

    public synchronized void detachPacketReceiver(Consumer<byte[]> receiver) {
        if (packetReceiver == receiver) packetReceiver = null;
    }

    public boolean sendIpv4(Consumer<byte[]> receiver, byte[] packet) {
        ClientPeerService current = peers;
        return packetReceiver == receiver && current != null && !stopped.get() && current.sendIpv4(packet);
    }

    private void own(ControlSession session) {
        active.set(session);
        if (stopped.get() && !provisioning.get()) {
            active.compareAndSet(session, null);
            try { session.close(); } catch (IOException ignored) { }
        }
    }

    public synchronized boolean connect() {
        if (shutdown.get()) return false;
        if (thread != null && thread.isAlive()) return false;
        stopped.set(false);
        state = new Snapshot("CONNECTING", "", List.of(), 0, "");
        thread = dev.radminlan.vendor.openradplugin.util.ThreadCompat.platformUnstarted("openrad-client-control", this::worker);
        thread.start();
        return true;
    }

    private void update(String phase, String vip, Membership m, String detail) {
        if (stopped.get()) return;
        List<String> networks = m == null ? List.of()
                : m.networks().values().stream()
                    .filter(network -> activeNetworkNames.contains(network.name()))
                    .map(Membership.Network::name).sorted().toList();
        ClientPeerService current = peers;
        Snapshot next = new Snapshot(phase, vip, networks,
                current == null ? 0 : current.connectedCount(), detail);
        ManagementView previous = published;
        List<ManagedNetwork> view = List.of();
        if (m != null) {
            Map<String, List<ManagedPeer>> byNetwork = new HashMap<>();
            for (Membership.Peer peer : m.peers().values()) {
                boolean connected = current != null && current.connected(peer.rid());
                long latency = connected ? current.latencyMs(peer.rid()) : -1;
                for (String id : peer.networks()) {
                    byNetwork.computeIfAbsent(id, key -> new ArrayList<>()).add(new ManagedPeer(peer.rid(), peer.name(),
                            peer.vip() == null ? "" : peer.vip().getHostAddress(), m.role(id, peer.rid()), connected, latency));
                }
            }
            view = m.networks().values().stream().sorted(Comparator.comparing(Membership.Network::name))
                    .map(network -> new ManagedNetwork(network.id(), network.name(), m.role(network.id(), m.ownRid()),
                            activeNetworkNames.contains(network.name()), byNetwork.getOrDefault(network.id(), List.of())))
                    .toList();
        }
        if (previous == null || previous.status() != state || !next.equals(previous.status())
                || !view.equals(previous.networks())) publish(next, view);
    }

    private synchronized void publish(Snapshot next, List<ManagedNetwork> view) {
        if (stopped.get()) return;
        published = new ManagementView(next, view);
        state = next;
    }

    /** Bound on the control thread's wait for a closed generation's workers (see PeerShutdown). */
    static final Duration PEER_STOP_WAIT = Duration.ofSeconds(15);

    private void worker() {
        FileChannel lockFile = null;
        try {
            CoordinatorEndpoint coordinator;
            try {
                coordinator = CoordinatorEndpoint.load(dataDirectory, ServiceConfig.BOOTSTRAP, ServiceConfig.PORT);
            } catch (IOException error) {
                if (!stopped.get()) state = new Snapshot("FAILED", "", List.of(), 0,
                        "Unable to load coordinator.properties or modulus.bin");
                return;
            }
            Path identityDirectory = coordinator.identityDirectory();
            Files.createDirectories(identityDirectory);
            Path identityLock = identityDirectory.resolve("identity.lock");
            if (Files.isSymbolicLink(identityLock))
                throw new IOException("identity lock symlink not permitted");
            lockFile = FileChannel.open(identityLock,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            if (lockFile.tryLock() == null) throw new IOException("OpenRad identity is already in use");
            networkChoicesFile = identityDirectory.resolve(NetworkChoices.FILE_NAME);
            networkOverrides.clear();
            try {
                networkOverrides.putAll(NetworkChoices.load(networkChoicesFile));
            } catch (IOException unreadable) {
                logger.accept("Unable to load saved network choices: " + unreadable.getClass().getSimpleName());
            }
            workerLocked(coordinator);
        } catch (OverlappingFileLockException inUse) {
            if (!stopped.get()) state = new Snapshot("FAILED", "", List.of(), 0,
                    "OpenRad identity is already in use (peer workers of a previous session may still be stopping)");
        } catch (Exception error) {
            if (!stopped.get()) state = new Snapshot("FAILED", "", List.of(), 0,
                    "Unable to own OpenRad identity (" + error.getClass().getSimpleName() + ")");
        } finally {
            releaseIdentity(lockFile);
        }
    }

    /**
     * Closing the channel releases the lock. It is handed to a keeper instead while a closed peer
     * generation still runs, so a new attachment can never share the identity with an old one.
     */
    private void releaseIdentity(FileChannel lockFile) {
        if (lockFile == null) return;
        List<ClientPeerService> pending = List.copyOf(stragglers);
        stragglers.clear();
        if (!pending.isEmpty()) {
            PeerShutdown.holdUntilStopped(pending, lockFile, logger);
            return;
        }
        try {
            lockFile.close();
        } catch (IOException failure) {
            logger.accept("Unable to release the OpenRad identity lock (" + failure.getClass().getSimpleName() + ")");
        }
    }

    private boolean abandonedByStragglers() {
        if (stragglers.isEmpty()) return false;
        logger.accept("OpenRad stopped: peer workers of the previous session did not terminate;"
                + " the identity stays locked until they do");
        if (!stopped.get()) state = new Snapshot("FAILED", "", List.of(), 0,
                "Peer workers of the previous session did not stop; Connect works again once they finish");
        return true;
    }

    /** A session that stayed attached this long was healthy: restart backoff (Rust daemon). */
    static final Duration STABLE_SESSION = Duration.ofSeconds(60);
    static final int RETRY_BASE_SECONDS = 3, RETRY_MAX_SECONDS = 60;

    /** Reconnect delay after the given number of consecutive failures (1-based). */
    static long retrySeconds(int failures) {
        int exponent = Math.max(0, Math.min(failures - 1, 5));
        return Math.min(RETRY_MAX_SECONDS, (long) RETRY_BASE_SECONDS << exponent);
    }

    private void workerLocked(CoordinatorEndpoint coordinator) {
        var relayGuard = new AcceptorRelayGuard();
        int failures = 0;
        while (!stopped.get()) {
            if (abandonedByStragglers()) break;
            long attachedAt = 0;
            try {
                // Closing or interrupting a registration after registration.pending is written
                // would discard an issued identity and block this profile for good. Disconnect
                // only flags it; the worker stops once the bounded registration has finished.
                Identity provisioned;
                controlStage = "authenticating";
                provisioning.set(true);
                try {
                    if (stopped.get()) break;
                    provisioned = Provisioner.loadOrRegister(
                            coordinator.identityDirectory(), "RadminLAN", coordinator.bootstrap(),
                            coordinator.port(), this::own, coordinator.modulus());
                } finally {
                    provisioning.set(false);
                    active.set(null);
                }
                if (stopped.get()) {
                    provisioned.close();
                    break;
                }
                try (Identity identity = provisioned;
                     Attachment connection = Attachment.connect(
                        identity, coordinator.port(), identity.nodeName(), this::own, coordinator.modulus())) {
                    own(connection.session());

                    attachedAt = System.nanoTime();
                    update("JOINING_NETWORKS", connection.vip().getHostAddress(),
                            connection.membership(), "");
                    runConnected(identity, connection, coordinator, relayGuard);
                }
                if (!stopped.get()) throw new IOException("control session ended");
            } catch (Exception ex) {
                if (stopped.get() || abandonedByStragglers()) break;
                String description = ex instanceof IOException && ex.getMessage() != null
                        ? ex.getMessage() : ex.getClass().getSimpleName();
                // Do not log remote record content, credentials or keys.
                if (description.contains("registration outcome unknown")) {
                    update("FAILED", "", null,
                            "Registration outcome unknown: keep registration.pending and recover identity.");
                    break;
                }
                if (ex instanceof ServerDisconnect disconnect && disconnect.replaced()) {
                    // Reconnecting would disconnect the other client, which would reconnect and
                    // disconnect this one: both would flap every few seconds. Wait for the user.
                    update("FAILED", "", null,
                            "This OpenRad identity connected from another client. Close the other"
                                    + " copy (or give it its own identity) and press Connect.");
                    logger.accept("OpenRad stopped: the same identity is attached from another"
                            + " client (copied state/identity.json?). Not reconnecting automatically.");
                    break;
                }
                if (attachedAt != 0 && System.nanoTime() - attachedAt >= STABLE_SESSION.toNanos())
                    failures = 0;
                failures = Math.min(failures + 1, 16);
                long delay = retrySeconds(failures);
                String reason = safeReason(ex);
                if (ex instanceof ServerDisconnect) reason += " during " + controlStage;
                catalog = new CatalogSnapshot("OFFLINE", List.of(), Set.of(), List.of());
                update("RETRY_WAIT", "", null, "Reconnect in " + delay + " s (" + reason + ")");
                logger.accept("Control connection interrupted (" + reason + "), retry in " + delay + "s");
                // Jitter spreads reconnects of many clients after a service restart.
                long until = System.nanoTime() + Duration.ofSeconds(delay).toNanos()
                        + java.util.concurrent.ThreadLocalRandom.current().nextLong(250_000_000L);
                try {
                    while (!stopped.get() && System.nanoTime() < until)
                        Thread.sleep(Math.max(1, Math.min(100, (until - System.nanoTime()) / 1_000_000L)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } finally {
                active.set(null);
            }
        }
        if (!state.phase().equals("FAILED"))
            state = new Snapshot("STOPPED", "", List.of(), 0, "");
    }

    /** Class name plus this client's own bounded message; never remote record content. */
    static String safeReason(Exception error) {
        String name = error.getClass().getSimpleName();
        String message = error.getMessage();
        if (!(error instanceof IOException) || message == null || message.isBlank()) return name;
        message = message.replaceAll("\\p{Cntrl}", " ");
        if (message.length() > 160) message = message.substring(0, 160) + "...";
        return name + ": " + message;
    }

    /** Outcome of one attachment record that is neither a catalog page nor a join reply. */
    enum ControlEvent { MEMBERSHIP, REFUSAL, PEER_RECORD }

    /**
     * Shared attachment dispatch, matching the Rust engine and the Paper plugin: operation 16 ends
     * the session, while a refusal (44) only answers one request (for example an acceptor relay
     * request) and keeps the session, its channels and players.
     */
    static ControlEvent classify(byte[] record) throws ServerDisconnect {
        return switch (TlvCodec.operation(record)) {
            case 38, 41, 42 -> ControlEvent.MEMBERSHIP;
            case ServerDisconnect.OPERATION -> throw ServerDisconnect.of(record);
            case 44 -> ControlEvent.REFUSAL;
            default -> ControlEvent.PEER_RECORD;
        };
    }

    private ControlEvent handle(byte[] record, Membership membership, ClientPeerService peerService) throws IOException {
        ControlEvent event;
        try {
            event = classify(record);
        } catch (ServerDisconnect disconnect) {
            if (!disconnect.replaced()) peerService.serverObjected();
            throw disconnect;
        }
        switch (event) {
            case MEMBERSHIP -> {
                if (TlvCodec.operation(record) == 38) membership.snapshot(record);
                else membership.changes(record);
            }
            case REFUSAL -> {
                if (peerService.serverObjected())
                    logger.accept(config.forceRelay()
                            ? "Service refused an acceptor relay request; incoming peers now depend on a relay offer of their own."
                            : "Service refused an acceptor relay request; using direct paths.");
            }
            case PEER_RECORD -> {
                try {
                    peerService.ingest(record);
                } catch (IllegalArgumentException malformed) {
                    // One malformed peer setup record must not end the shared session.
                    long now = System.nanoTime(), last = lastIncomingRejectLog.get();
                    if ((last == 0 || now - last >= Duration.ofSeconds(10).toNanos())
                            && lastIncomingRejectLog.compareAndSet(last, now))
                        logger.accept("Incoming peer request rejected (" + safeReason(malformed) + ")");
                }
            }
        }
        return event;
    }

    private void runConnected(Identity identity, Attachment connection, CoordinatorEndpoint coordinator,
                              AcceptorRelayGuard relayGuard) throws IOException {
        relayGuard.startAttachment();
        try (ClientPeerService peerService = new ClientPeerService(identity, connection, config, coordinator, relayGuard, () -> packetReceiver)) {
            peers = peerService;
            Set<String> initialTargets = resolveNetworks(connection, peerService);
            var targets = new LinkedHashSet<>(initialTargets);
            var joined = connection.membership().networks().values().stream()
                    .map(Membership.Network::name).collect(java.util.stream.Collectors.toSet());
            for (var entry : networkOverrides.entrySet()) {
                if (entry.getValue() && joined.contains(entry.getKey())) targets.add(entry.getKey());
                else targets.remove(entry.getKey());
            }
            activeNetworkNames = Set.copyOf(targets);
            peerService.selectNetworks(activeNetworkNames);
            runConnected(connection, peerService, targets);
        } finally {
            ClientPeerService current = peers;
            peers = null;
            activeNetworkNames = Set.of();
            rejectPending();
            if (current != null) {
                current.close();
                // identity.lock must not pass to a new attachment while an old peer worker may
                // still use this identity. The wait is bounded: a worker that never stops leaves the
                // lock with a keeper (see worker()) instead of pinning this control thread forever.
                if (!PeerShutdown.await(current, PEER_STOP_WAIT, logger)) stragglers.add(current);
            }
        }
    }

    record TargetSelection(Set<String> names, List<String> problems) {
        TargetSelection { names = Set.copyOf(names); problems = List.copyOf(problems); }
    }

    static TargetSelection selectNetworkNames(ClientConfig config, List<MinecraftNetworks.Entry> entries) {
        var selected = MinecraftNetworks.select(entries, config.minecraftSelectors(), config.maxNetworks());
        Set<String> names = new LinkedHashSet<>(config.networks());
        selected.networks().forEach(entry -> names.add(entry.name()));
        if (names.size() > config.maxNetworks())
            throw new IllegalArgumentException("Selected exact and Minecraft networks exceed max-networks");
        return new TargetSelection(names, selected.unmatched().stream()
                .map(selector -> "Selector absent from catalog: " + selector).toList());
    }

    private Set<String> resolveNetworks(Attachment connection, ClientPeerService peerService) throws IOException {
        CatalogSnapshot previous = catalog;
        catalog = new CatalogSnapshot("LOADING", previous.entries(), Set.copyOf(config.networks()), List.of());
        controlStage = "Minecraft catalog query";
        List<MinecraftNetworks.Entry> entries;
        try {
            entries = MinecraftCatalogQuery.fetch(connection.session(),
                    record -> handle(record, connection.membership(), peerService), stopped::get);
        } catch (IOException | IllegalArgumentException failure) {
            if (stopped.get()) throw new InterruptedIOException("OpenRad catalog cancelled");
            if (failure instanceof ServerDisconnect || failure instanceof EOFException
                    || failure instanceof java.net.SocketException)
                throw (IOException) failure;
            // Selector-only profiles have nothing to join without the catalog: reconnect with
            // backoff instead of reporting CONNECTED with zero networks and never retrying.
            if (config.networks().isEmpty() && !config.minecraftSelectors().isEmpty())
                throw new IOException("Minecraft catalog unavailable", failure);
            catalog = new CatalogSnapshot("FAILED", previous.entries(), Set.copyOf(config.networks()),
                    List.of("Minecraft catalog unavailable (" + failure.getClass().getSimpleName() + "); exact network names remain active"));
            return Set.copyOf(config.networks());
        }
        try {
            TargetSelection selection = selectNetworkNames(config, entries);
            catalog = new CatalogSnapshot(selection.problems().isEmpty() ? "READY" : "READY_WITH_WARNINGS",
                    entries, selection.names(), selection.problems());
            return selection.names();
        } catch (IllegalArgumentException limit) {
            catalog = new CatalogSnapshot("SELECTION_REJECTED", entries, Set.copyOf(config.networks()),
                    List.of("Minecraft selection exceeds max-networks; increase the limit or narrow the selectors"));
            return Set.copyOf(config.networks());
        }
    }

    private void runConnected(Attachment connection, ClientPeerService peerService, Set<String> targets) throws IOException {
        ControlSession session = connection.session();
        Membership membership = connection.membership();
        long heartbeat = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        long id = 1;
        int sequence = 1;
        List<String> problems = new ArrayList<>(catalog.problems());
        // Automatic rejoin is allowed only for explicitly supplied startup networks.
        // Runtime selections become eligible again if membership still exists.
        List<String> ordered = targets.stream().filter(name -> !networkOverrides.containsKey(name))
                .sorted().toList();
        int joinIndex = 0;
        for (String network : ordered) {
            if (stopped.get()) return;
            joinIndex++;
            if (membership.networks().values().stream()
                    .anyMatch(n -> n.name().equals(network))) continue;
            controlStage = "join of network \"" + network + "\" (" + joinIndex + "/" + ordered.size() + ")";
            try (NetworkJoin join = new NetworkJoin(network, null, id++, sequence++)) {
                session.send(join.start());
                long until = System.nanoTime() + Duration.ofSeconds(12).toNanos();
                while (!join.complete() && !stopped.get()) {
                    if (System.nanoTime() >= until) {
                        problems.add(network + ": timed out");
                        break;
                    }
                    if (System.nanoTime() >= heartbeat) {
                        session.send(op(4));
                        heartbeat = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                    }
                    if (!session.ready(Duration.ofMillis(100))) continue;
                    byte[] msg = session.receive();
                    handle(msg, membership, peerService);
                    try {
                        byte[] reply = join.handle(msg, membership);
                        if (reply != null) session.send(reply);
                    } catch (NetworkJoin.Refused rejected) {
                        problems.add(network + ": refused (" + rejected.code() + ")");
                        break;
                    }
                }
                if (join.pendingApproval())
                    problems.add(network + ": awaiting administrator approval");
            }
            // Membership and authenticated VIP become visible while joins are
            // still in progress; do not leave the UI stuck showing zero networks.
            update("JOINING_NETWORKS", connection.vip().getHostAddress(),
                    membership, "");
        }
        controlStage = "connected session";
        String ip = connection.vip().getHostAddress();
        update("CONNECTED", ip, membership,
                problems.isEmpty() ? "Control authenticated; " + peerService.connectedCount() + " authenticated peer channel(s)"
                        : String.join("; ", problems));
        long snapshot = System.nanoTime();
        long lastSnapshot = snapshot;
        long lastControl = snapshot;
        PeerScanSchedule scans = new PeerScanSchedule(snapshot);
        while (!stopped.get()) {
            Request request = management.poll();
            if (request != null) {
                boolean ok = false;
                try {
                    ok = applyManagement(request, connection, peerService, id++, sequence++);
                    peerService.tick(membership);
                    update("CONNECTED", ip, membership, "");
                } catch (IllegalArgumentException rejected) {
                    logger.accept("Network operation rejected (invalid input or response)");
                } finally {
                    request.finish(ok);
                }
                lastControl = System.nanoTime();
                heartbeat = lastControl;
            }
            long now = System.nanoTime();
            if (now >= heartbeat) {
                session.send(op(4));
                heartbeat = now + Duration.ofSeconds(10).toNanos();
            }
            // Wake for a due scan/snapshot instead of always waiting the full 100 ms.
            long wait = Math.max(1_000_000L, Math.min(100_000_000L,
                    Math.min(scans.untilNext(now), snapshot - now)));
            if (session.ready(Duration.ofNanos(wait))) {
                ControlEvent event = handle(session.receive(), membership, peerService);
                lastControl = System.nanoTime();
                // Coalesce bursts of membership records: the O(roster) peer scan and the status
                // snapshot are rate limited instead of running after every record. A refusal
                // changes neither the roster nor any peer setup.
                snapshot = Math.min(snapshot, Math.max(lastControl + Duration.ofMillis(10).toNanos(),
                        lastSnapshot + Duration.ofMillis(100).toNanos()));
                if (event != ControlEvent.REFUSAL) scans.recordHandled(lastControl);
            }
            if (scans.due(System.nanoTime())) {
                peerService.tick(membership);
                scans.scanned(System.nanoTime());
            }
            if (System.nanoTime() - lastControl > Duration.ofSeconds(90).toNanos())
                throw new IOException("control heartbeat timeout");
            if (System.nanoTime() >= snapshot) {
                update("CONNECTED", ip, membership,
                        problems.isEmpty() ? "Control authenticated; " + peerService.connectedCount() + " authenticated peer channel(s)" : String.join("; ", problems));
                lastSnapshot = System.nanoTime();
                snapshot = lastSnapshot + Duration.ofSeconds(1).toNanos();
            }
        }
    }

    private boolean applyManagement(Request request, Attachment connection, ClientPeerService peerService,
                                    long id, int sequence) throws IOException {
        ControlSession session = connection.session();
        Membership membership = connection.membership();
        var existing = membership.networks().get(request.target());
        if (request.action() == Action.ENABLE || request.action() == Action.DISABLE) {
            if (existing == null) return false;
            setSelected(peerService, existing.name(), request.action() == Action.ENABLE);
            return true;
        }
        long until = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        if (request.action() == Action.JOIN) {
            if (membership.networks().values().stream().anyMatch(network -> network.name().equals(request.target()))) {
                setSelected(peerService, request.target(), true);
                return true;
            }
            try (NetworkJoin join = new NetworkJoin(request.target(), request.password(), id, sequence)) {
                session.send(join.start());
                while (!stopped.get() && System.nanoTime() < until && !join.complete()) {
                    if (!session.ready(Duration.ofMillis(100))) continue;
                    byte[] record = session.receive();
                    handle(record, membership, peerService);
                    byte[] reply = join.handle(record, membership);
                    if (reply != null) session.send(reply);
                }
                if (!join.complete() || join.pendingApproval() || stopped.get()
                        || membership.networks().values().stream().noneMatch(network -> network.name().equals(request.target()))) return false;
                setSelected(peerService, request.target(), true);
                return true;
            } catch (NetworkJoin.Refused rejected) {
                return false;
            }
        }
        if (existing == null && request.action() != Action.CREATE) return false;
        if (request.action() == Action.KICK) {
            var member = membership.peers().get(request.memberId());
            if (!Integer.valueOf(2).equals(membership.role(existing.id(), membership.ownRid()))
                    || request.memberId() == membership.ownRid() || member == null || !member.networks().contains(existing.id())) {
                return false;
            }
        }
        try (NetworkManagement operation = switch (request.action()) {
            case CREATE -> NetworkManagement.createPrivate(request.target(), request.password(), id, sequence);
            case LEAVE -> NetworkManagement.leave(existing.id(), id, sequence);
            case DELETE -> NetworkManagement.delete(existing.id(), id, sequence);
            case KICK -> NetworkManagement.removeMember(existing.id(), request.memberId(), id, sequence);
            default -> throw new IllegalArgumentException("unknown management action");
        }) {
            session.send(operation.start());
            while (!stopped.get() && System.nanoTime() < until) {
                if (!session.ready(Duration.ofMillis(100))) continue;
                byte[] record = session.receive();
                handle(record, membership, peerService);
                var result = operation.handle(record, membership);
                if (result == null) continue;
                if (!result.success()) return false;
                if (request.action() == Action.CREATE) setSelected(peerService, request.target(), true);
                else if (request.action() != Action.KICK) setSelected(peerService, existing.name(), false);
                return true;
            }
            return false;
        }
    }

    private void setSelected(ClientPeerService peerService, String name, boolean enabled) {
        var selected = new HashSet<>(activeNetworkNames);
        if (enabled) selected.add(name);
        else selected.remove(name);
        networkOverrides.put(name, enabled);
        try {
            NetworkChoices.save(networkChoicesFile, networkOverrides);
        } catch (IOException unwritable) {
            logger.accept("Unable to save network choices: " + unwritable.getClass().getSimpleName());
        }
        activeNetworkNames = Set.copyOf(selected);
        peerService.selectNetworks(activeNetworkNames);
    }

    public synchronized void disconnect() {
        Thread running = thread;
        if (running == null) return;
        stopped.set(true);
        state = new Snapshot("STOPPED", "", List.of(), 0, "");
        catalog = new CatalogSnapshot("OFFLINE", List.of(), Set.of(), List.of());
        rejectPending();
        if (provisioning.get()) return;
        ClientPeerService current = peers;
        if (current != null) current.close();
        ControlSession session = active.getAndSet(null);
        if (session != null) {
            try { session.close(); } catch (IOException ignored) { }
        }
        running.interrupt();
    }

    @Override
    public void close() {
        shutdown.set(true);
        disconnect();
    }

    /** Optional worker-side shutdown wait; UI callers should use close() without blocking. */
    public boolean awaitStopped(Duration timeout) throws InterruptedException {
        Thread running = thread;
        if (running == null) return true;
        if (timeout.isNegative() || timeout.isZero()) return !running.isAlive();
        running.join(timeout.toMillis(), timeout.toNanosPart() % 1_000_000);
        return !running.isAlive();
    }
}
