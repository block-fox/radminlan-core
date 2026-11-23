import Foundation

struct TunnelView: Decodable {
    struct Status: Decodable {
        struct Network: Decodable, Identifiable {
            let name: String
            let network_id: String
            var id: String { network_id }
        }
        let phase: String
        var vip: String?
        var message: String?
        var networks: [Network]?
        var connectedPeers: Int?
    }
    struct Operation: Decodable {
        let id: UInt64
        let ok: Bool
        let message: String
    }
    let status: Status
    var operation: Operation?
}

struct NetworkAction: Codable {
    let action: String
    let target: String
    var password: String?
}
