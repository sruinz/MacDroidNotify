import Foundation

public final class NotificationDeduplicator {
    private let windowSeconds: TimeInterval
    private var seenAt: [String: TimeInterval] = [:]

    public init(windowSeconds: TimeInterval) {
        self.windowSeconds = windowSeconds
    }

    public func shouldAccept(id: String, now: TimeInterval = Date().timeIntervalSince1970) -> Bool {
        prune(now: now)
        if let previous = seenAt[id], now - previous <= windowSeconds {
            return false
        }
        seenAt[id] = now
        return true
    }

    private func prune(now: TimeInterval) {
        seenAt = seenAt.filter { now - $0.value <= windowSeconds }
    }
}
