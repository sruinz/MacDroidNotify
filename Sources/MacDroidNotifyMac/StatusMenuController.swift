import AppKit

final class StatusMenuController: NSObject {
    var onShowPairing: (() -> Void)?
    var onSendClipboard: (() -> Void)?
    var onCheckNotifications: (() -> Void)?
    var onSendTestNotification: (() -> Void)?
    var onChangePort: (() -> Void)?

    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let appMenuItem = NSMenuItem(title: AppVersion.displayName, action: nil, keyEquivalent: "")
    private let statusMenuItem = NSMenuItem(title: "시작 중...", action: nil, keyEquivalent: "")

    var currentStatus: String {
        statusMenuItem.title
    }

    override init() {
        super.init()
        statusItem.length = NSStatusItem.squareLength
        statusItem.button?.image = Self.statusIcon()
        statusItem.button?.toolTip = AppVersion.displayName

        let menu = NSMenu()
        appMenuItem.isEnabled = false
        menu.addItem(appMenuItem)

        statusMenuItem.isEnabled = false
        menu.addItem(statusMenuItem)
        menu.addItem(NSMenuItem.separator())

        let pairingItem = NSMenuItem(title: "페어링 QR 보기", action: #selector(showPairing), keyEquivalent: "p")
        pairingItem.target = self
        menu.addItem(pairingItem)

        let portItem = NSMenuItem(title: "포트 변경...", action: #selector(changePort), keyEquivalent: "")
        portItem.target = self
        menu.addItem(portItem)

        let clipboardItem = NSMenuItem(title: "Mac 클립보드를 Android로 보내기", action: #selector(sendClipboard), keyEquivalent: "c")
        clipboardItem.target = self
        menu.addItem(clipboardItem)

        let notificationStatusItem = NSMenuItem(title: "Mac 알림 상태 확인", action: #selector(checkNotifications), keyEquivalent: "n")
        notificationStatusItem.target = self
        menu.addItem(notificationStatusItem)

        let notificationTestItem = NSMenuItem(title: "Mac 테스트 알림 보내기", action: #selector(sendTestNotification), keyEquivalent: "t")
        notificationTestItem.target = self
        menu.addItem(notificationTestItem)

        menu.addItem(NSMenuItem.separator())
        menu.addItem(NSMenuItem(title: "종료", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
        statusItem.menu = menu
    }

    func updateStatus(_ text: String) {
        statusMenuItem.title = text
    }

    @objc private func showPairing() {
        onShowPairing?()
    }

    @objc private func sendClipboard() {
        onSendClipboard?()
    }

    @objc private func checkNotifications() {
        onCheckNotifications?()
    }

    @objc private func sendTestNotification() {
        onSendTestNotification?()
    }

    @objc private func changePort() {
        onChangePort?()
    }

    private static func statusIcon() -> NSImage {
        let image = NSImage(size: NSSize(width: 18, height: 18))
        image.lockFocus()
        NSColor.black.setStroke()
        NSColor.black.setFill()

        let mac = NSBezierPath(roundedRect: NSRect(x: 2.5, y: 5.5, width: 9, height: 7), xRadius: 1.4, yRadius: 1.4)
        mac.lineWidth = 1.6
        mac.stroke()
        NSBezierPath(rect: NSRect(x: 5.2, y: 3.4, width: 3.6, height: 1.4)).fill()

        let phone = NSBezierPath(roundedRect: NSRect(x: 11, y: 3, width: 4.5, height: 12), xRadius: 1.2, yRadius: 1.2)
        phone.lineWidth = 1.5
        phone.stroke()

        let signal = NSBezierPath()
        signal.move(to: NSPoint(x: 6, y: 14.5))
        signal.curve(to: NSPoint(x: 12, y: 14.5), controlPoint1: NSPoint(x: 8, y: 16.2), controlPoint2: NSPoint(x: 10, y: 16.2))
        signal.lineWidth = 1.5
        signal.lineCapStyle = .round
        signal.stroke()

        image.unlockFocus()
        image.isTemplate = true
        return image
    }
}
