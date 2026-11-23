import Foundation
import NetworkExtension
import SwiftUI

@main
struct RadminLANApp: App {
    @StateObject private var client = TunnelManager()

    var body: some Scene {
        WindowGroup {
            NavigationStack {
                Form {
                    Section("VPN") {
                        Text(client.status)
                        if let ip = client.view?.status.vip { LabeledContent("Virtual IP", value: ip) }
                        if let peers = client.view?.status.connectedPeers {
                            LabeledContent("Connected peers", value: String(peers))
                        }
                        Button(client.isActive ? "Disconnect" : "Connect") { client.toggle() }
                    }
                    Section("Join a network") {
                        TextField("Network name", text: $client.name).autocorrectionDisabled()
                        SecureField("Password", text: $client.password)
                        Button("Join") { client.submit("join") }.disabled(!client.canSubmit)
                        Button("Create private network") { client.submit("create") }
                            .disabled(!client.canSubmit || client.password.isEmpty)
                    }
                    Section("My networks") {
                        ForEach(client.view?.status.networks ?? []) { network in
                            HStack {
                                Text(network.name)
                                Spacer()
                                Button("Leave", role: .destructive) { client.leave(network) }.buttonStyle(.borderless)
                            }
                        }
                    }
                    if !client.feedback.isEmpty { Text(client.feedback) }
                }
                .navigationTitle("RadminLAN")
                .task { await client.refresh() }
            }
        }
    }
}

@MainActor
private final class TunnelManager: ObservableObject {
    @Published var name = ""
    @Published var password = ""
    @Published var feedback = ""
    @Published private(set) var view: TunnelView?
    @Published private(set) var state: NEVPNStatus = .disconnected
    private var manager: NETunnelProviderManager?
    private var pending: UInt64?
    private var pendingSince = Date.distantPast
    private var switching = false

    var isActive: Bool { state == .connected || state == .connecting || state == .reasserting }
    var canSubmit: Bool { state == .connected && pending == nil && !name.trimmingCharacters(in: .whitespaces).isEmpty }
    var status: String {
        switch state {
        case .connected: return "Connected"
        case .connecting, .reasserting: return "Connecting…"
        case .disconnecting: return "Disconnecting…"
        default: return "Disconnected"
        }
    }

    func toggle() {
        guard !switching, state != .disconnecting else { return }
        switching = true
        Task {
            defer { switching = false }
            do {
                if isActive { manager?.connection.stopVPNTunnel(); return }
                let manager = try await configuredManager()
                try manager.connection.startVPNTunnel()
                state = manager.connection.status
            } catch { feedback = error.localizedDescription }
        }
    }

    func refresh() async {
        if manager == nil { manager = try? await NETunnelProviderManager.loadAllFromPreferences().first }
        while !Task.isCancelled {
            if let connection = manager?.connection as? NETunnelProviderSession {
                state = connection.status
                if state == .connected, let payload = try? await request(connection, Data([0])),
                   let updated = try? JSONDecoder().decode(TunnelView.self, from: payload) {
                    view = updated
                    if let operation = updated.operation, operation.id == pending {
                        feedback = operation.message
                        pending = nil
                    } else if pending != nil, Date().timeIntervalSince(pendingSince) > 35 {
                        feedback = "Network request timed out"
                        pending = nil
                    }
                } else if state == .disconnected || state == .invalid {
                    view = nil
                    pending = nil
                }
            }
            try? await Task.sleep(nanoseconds: 500_000_000)
        }
    }

    func submit(_ action: String) {
        let target = name.trimmingCharacters(in: .whitespaces)
        let secret = password.isEmpty ? nil : password
        password = ""
        send(NetworkAction(action: action, target: target, password: secret))
    }

    func leave(_ network: TunnelView.Status.Network) {
        send(NetworkAction(action: "leave", target: network.id))
    }

    private func send(_ action: NetworkAction) {
        guard pending == nil, state == .connected, let connection = manager?.connection as? NETunnelProviderSession else { return }
        pending = 0
        pendingSince = Date()
        Task {
            do {
                let data = try JSONEncoder().encode(action)
                guard let reply = try await request(connection, data), let value = String(data: reply, encoding: .utf8),
                      let ticket = UInt64(value), ticket != 0 else {
                    feedback = "The network request was rejected"
                    pending = nil
                    return
                }
                pending = ticket
                feedback = "Waiting for Radmin…"
            } catch {
                pending = nil
                feedback = error.localizedDescription
            }
        }
    }

    private func configuredManager() async throws -> NETunnelProviderManager {
        let existing = try await NETunnelProviderManager.loadAllFromPreferences().first
        let result = existing ?? NETunnelProviderManager()
        if existing == nil || !result.isEnabled {
            let proto = NETunnelProviderProtocol()
            proto.providerBundleIdentifier = Bundle.main.bundleIdentifier.map { $0 + ".PacketTunnel" }
            proto.serverAddress = "Radmin"
            result.protocolConfiguration = proto
            result.localizedDescription = "RadminLAN"
            result.isEnabled = true
            try await result.saveToPreferences()
            try await result.loadFromPreferences()
        }
        manager = result
        return result
    }

    private func request(_ session: NETunnelProviderSession, _ data: Data) async throws -> Data? {
        try await withCheckedThrowingContinuation { continuation in
            do { try session.sendProviderMessage(data) { continuation.resume(returning: $0) } }
            catch { continuation.resume(throwing: error) }
        }
    }
}
