import Foundation

public struct PendingClipboardQueue {
    private var payload: ClipboardPayload?

    public init() {}

    public mutating func enqueue(_ payload: ClipboardPayload) {
        self.payload = payload
    }

    public mutating func take() -> ClipboardPayload? {
        let current = payload
        payload = nil
        return current
    }
}
