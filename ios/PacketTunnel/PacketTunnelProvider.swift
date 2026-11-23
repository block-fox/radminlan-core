import Foundation
import NetworkExtension
import Darwin

final class PacketTunnelProvider: NEPacketTunnelProvider {
    private let queue = DispatchQueue(label: "dev.radminlan.ios.packet-flow")
    private var core: OpaquePointer?
    private var timer: DispatchSourceTimer?
    private var startup: ((Error?) -> Void)?
    private var deadline = DispatchTime.now()
    private var configured = false
    private var generation = 0
    private var activeAddress: String?

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        queue.async {
            guard self.core == nil else {
                completionHandler(self.failure("A RadminLAN session is already active"))
                return
            }
            do {
                let profile = try FileManager.default.url(for: .applicationSupportDirectory,
                    in: .userDomainMask, appropriateFor: nil, create: true).appendingPathComponent("Core", isDirectory: true)
                guard let handle = profile.path.withCString({ rlc_start($0) }) else {
                    completionHandler(self.failure("Unable to start RadminLAN Core"))
                    return
                }
                self.generation += 1
                self.core = handle
                self.startup = completionHandler
                self.deadline = .now() + 125
                let timer = DispatchSource.makeTimerSource(queue: self.queue)
                timer.schedule(deadline: .now(), repeating: .milliseconds(20), leeway: .milliseconds(4))
                timer.setEventHandler { [weak self] in self?.tick() }
                self.timer = timer
                timer.resume()
            } catch { completionHandler(error) }
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        queue.async { self.finish(self.failure("VPN stopped"), onStopped: completionHandler) }
    }

    private func tick() {
        guard let core else { return }
        if let bytes = status(core), let view = try? JSONDecoder().decode(TunnelView.self, from: bytes) {
            if view.status.phase == "failed" {
                let error = failure(view.status.message ?? "The Radmin session ended")
                let running = startup == nil
                finish(error)
                if running { cancelTunnelWithError(error) }
                return
            }
            if view.status.phase == "online", let ip = view.status.vip, activeAddress == nil {
                activeAddress = ip
                configure(ip, generation: generation)
            }
        }
        if startup != nil, DispatchTime.now() > deadline {
            finish(failure("Radmin connection timed out"))
            return
        }
        guard configured else { return }
        var buffer = [UInt8](repeating: 0, count: 1500)
        var packets: [Data] = []
        for _ in 0..<64 {
            let length = buffer.withUnsafeMutableBufferPointer { rlc_receive(core, $0.baseAddress, $0.count) }
            if length == 0 { break }
            packets.append(Data(buffer.prefix(length)))
        }
        if !packets.isEmpty {
            packetFlow.writePackets(packets, withProtocols: Array(repeating: NSNumber(value: AF_INET), count: packets.count))
        }
    }

    private func configure(_ address: String, generation: Int) {
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "148.113.190.78")
        let ipv4 = NEIPv4Settings(addresses: [address], subnetMasks: ["255.255.255.255"])
        ipv4.includedRoutes = [NEIPv4Route(destinationAddress: "26.0.0.0", subnetMask: "255.0.0.0")]
        settings.ipv4Settings = ipv4
        settings.mtu = 1500
        setTunnelNetworkSettings(settings) { error in
            self.queue.async {
                guard self.generation == generation, self.core != nil else { return }
                if let error { self.finish(error); return }
                self.configured = true
                let start = self.startup
                self.startup = nil
                start?(nil)
                self.readPackets(generation: generation)
            }
        }
    }

    private func readPackets(generation: Int) {
        packetFlow.readPackets { packets, protocols in
            self.queue.async {
                guard let core = self.core, self.configured, self.generation == generation else { return }
                for (packet, family) in zip(packets, protocols) where family.int32Value == AF_INET {
                    _ = packet.withUnsafeBytes { buffer in
                        rlc_send(core, buffer.bindMemory(to: UInt8.self).baseAddress, buffer.count)
                    }
                }
                self.readPackets(generation: generation)
            }
        }
    }

    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        queue.async {
            guard let core = self.core else { completionHandler?(nil); return }
            if messageData == Data([0]) {
                completionHandler?(self.status(core))
            } else if self.configured, messageData.count <= 4096 {
                let ticket = messageData.withUnsafeBytes { buffer in
                    rlc_manage(core, buffer.bindMemory(to: UInt8.self).baseAddress, buffer.count)
                }
                completionHandler?(String(ticket).data(using: .utf8))
            } else { completionHandler?(nil) }
        }
    }

    private func status(_ core: OpaquePointer) -> Data? {
        let expected = rlc_status(core, nil, 0)
        guard expected > 0, expected <= 512 * 1024 else { return nil }
        var bytes = Data(count: expected)
        let count = bytes.withUnsafeMutableBytes { rlc_status(core, $0.bindMemory(to: UInt8.self).baseAddress, $0.count) }
        guard count > 0, count <= expected else { return nil }
        return bytes.prefix(count)
    }

    private func failure(_ detail: String) -> NSError {
        NSError(domain: "dev.radminlan.ios", code: 1, userInfo: [NSLocalizedDescriptionKey: detail])
    }

    private func finish(_ error: Error, onStopped: (() -> Void)? = nil) {
        generation += 1
        configured = false
        activeAddress = nil
        timer?.cancel()
        timer = nil
        let start = startup
        startup = nil
        start?(error)
        guard let handle = core else { onStopped?(); return }
        core = nil
        DispatchQueue.global(qos: .utility).async {
            rlc_stop(handle)
            onStopped?()
        }
    }
}
