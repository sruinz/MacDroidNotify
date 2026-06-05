import AppKit
import CoreImage
import Foundation
import MacDroidNotifyCore

final class AppDelegate: NSObject, NSApplicationDelegate, TcpNotificationServerDelegate {
    private let tokenStore = TokenStore()
    private let portStore = PortStore()
    private let presenter = NotificationPresenter()
    private let deduplicator = NotificationDeduplicator(windowSeconds: 30)

    private var port: UInt16 = NetworkPort.defaultValue
    private var token = Data()
    private var server: TcpNotificationServer?
    private var menu: StatusMenuController?
    private var pairingWindowController: PairingWindowController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        token = tokenStore.loadOrCreateToken()
        port = portStore.loadPort()
        presenter.requestAuthorization()

        let menu = StatusMenuController()
        menu.onShowPairing = { [weak self] in self?.showPairingInfo() }
        menu.onSendClipboard = { [weak self] in self?.sendMacClipboardToAndroid() }
        menu.onCheckNotifications = { [weak self] in self?.showNotificationStatus() }
        menu.onSendTestNotification = { [weak self] in self?.sendMacTestNotification() }
        menu.onChangePort = { [weak self] in self?.showPortEditor() }
        self.menu = menu

        startServer(port: port)
    }

    private func startServer(port: UInt16) {
        server?.stop()

        let server = TcpNotificationServer(port: port, token: token)
        server.delegate = self
        self.server = server

        do {
            try server.start()
            menu?.updateStatus("대기 중 :\(port)")
        } catch {
            menu?.updateStatus("서버 시작 실패 :\(port)")
            showError("리스너를 시작하지 못했습니다: \(error.localizedDescription)")
        }
    }

    func serverDidUpdateStatus(_ status: String) {
        DispatchQueue.main.async { [weak self] in
            self?.menu?.updateStatus(status)
        }
    }

    func serverDidReceiveNotification(_ payload: NotificationPayload) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            guard deduplicator.shouldAccept(id: payload.id) else { return }
            menu?.updateStatus("Android 알림 수신: \(payload.appName)")
            presenter.present(payload: payload.limited()) { [weak self] result in
                DispatchQueue.main.async {
                    switch result {
                    case .success:
                        self?.menu?.updateStatus("Mac 알림 요청됨: \(payload.appName)")
                    case let .failure(error):
                        self?.menu?.updateStatus("Mac 알림 실패")
                        self?.showError("Mac 알림을 등록하지 못했습니다: \(error.localizedDescription)")
                    }
                }
            }
        }
    }

    func serverDidReceiveClipboardText(_ text: String) {
        DispatchQueue.main.async { [weak self] in
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(text, forType: .string)
            self?.menu?.updateStatus("클립보드 수신됨")
        }
    }

    private func showPairingInfo() {
        let pairingURL = makePairingURL()
        guard let qrImage = makeQRCodeImage(for: pairingURL) else {
            showError("QR 이미지를 만들지 못했습니다.\n\n\(pairingURL)")
            return
        }

        let controller = pairingWindowController ?? PairingWindowController()
        pairingWindowController = controller
        controller.show(
            pairingURL: pairingURL,
            qrImage: qrImage,
            status: menu?.currentStatus ?? "알 수 없음"
        )
    }

    private func sendMacClipboardToAndroid() {
        guard let text = currentMacClipboardText() else {
            showError("Mac 클립보드에 텍스트가 없습니다.\n\n\(macClipboardDebugDescription())")
            return
        }

        let payload = ClipboardPayload(text: text, timestampMillis: currentTimeMillis())
        do {
            let validated = try payload.validated()
            try server?.sendClipboardToAndroid(validated)
            menu?.updateStatus("클립보드 전송됨")
        } catch ProtocolError.clipboardTooLarge {
            showError("클립보드 텍스트가 32 KiB보다 큽니다.")
        } catch {
            showError("클립보드를 보내지 못했습니다: \(error.localizedDescription)")
        }
    }

    private func showPortEditor() {
        let alert = NSAlert()
        alert.messageText = "Mac 리스너 포트 변경"
        alert.informativeText = """
        현재 포트: \(port)

        1024부터 65535 사이의 포트를 입력하세요. 포트를 바꾸면 Android 앱에서 QR로 다시 페어링해야 합니다.
        """
        alert.addButton(withTitle: "저장 후 재시작")
        alert.addButton(withTitle: "취소")

        let input = NSTextField(string: "\(port)")
        input.frame = NSRect(x: 0, y: 0, width: 220, height: 24)
        input.placeholderString = "\(NetworkPort.defaultValue)"
        alert.accessoryView = input

        guard alert.runModal() == .alertFirstButtonReturn else { return }
        guard let newPort = NetworkPort.parseUserInput(input.stringValue) else {
            showError("포트는 1024부터 65535 사이의 숫자여야 합니다.")
            return
        }
        guard newPort != port else {
            menu?.updateStatus("대기 중 :\(port)")
            return
        }

        port = newPort
        portStore.save(newPort)
        pairingWindowController?.close()
        startServer(port: newPort)
        showInfo("포트를 \(newPort)번으로 변경했습니다.\n\nAndroid 앱에서 새 QR로 다시 페어링하세요.")
    }

    private func currentMacClipboardText() -> String? {
        let pasteboard = NSPasteboard.general
        if let text = pasteboard.string(forType: .string), isSendableClipboardText(text) {
            return text
        }
        if let text = pasteboard.string(forType: .URL), isSendableClipboardText(text) {
            return text
        }
        if let text = pasteboard.string(forType: .fileURL), isSendableClipboardText(text) {
            return text
        }

        let objects = pasteboard.readObjects(forClasses: [NSString.self, NSURL.self], options: nil) ?? []
        for object in objects {
            if let text = object as? String, isSendableClipboardText(text) {
                return text
            }
            if let text = object as? NSString, isSendableClipboardText(text as String) {
                return text as String
            }
            if let url = object as? URL {
                let text = url.absoluteString
                if isSendableClipboardText(text) {
                    return text
                }
            }
        }

        return nil
    }

    private func isSendableClipboardText(_ text: String) -> Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func macClipboardDebugDescription() -> String {
        let types = NSPasteboard.general.types?.map(\.rawValue).joined(separator: "\n- ") ?? "없음"
        return "클립보드 타입:\n- \(types)"
    }

    private func showNotificationStatus() {
        presenter.notificationStatus { [weak self] status in
            DispatchQueue.main.async {
                guard let self else { return }
                let alert = NSAlert()
                alert.messageText = "Mac 알림 상태"
                alert.informativeText = """
                \(status)

                번들 ID: \(Bundle.main.bundleIdentifier ?? "없음")
                실행 경로: \(Bundle.main.bundlePath)
                """
                alert.addButton(withTitle: "닫기")
                alert.addButton(withTitle: "권한 다시 요청")
                alert.addButton(withTitle: "알림 설정 열기")

                let response = alert.runModal()
                if response == .alertSecondButtonReturn {
                    self.presenter.requestAuthorization()
                    self.menu?.updateStatus("Mac 알림 권한 요청됨")
                } else if response == .alertThirdButtonReturn {
                    self.openNotificationSettings()
                }
            }
        }
    }

    private func sendMacTestNotification() {
        presenter.presentLocalTest { [weak self] result in
            DispatchQueue.main.async {
                switch result {
                case .success:
                    self?.menu?.updateStatus("Mac 테스트 알림 요청됨")
                case let .failure(error):
                    self?.menu?.updateStatus("Mac 테스트 알림 실패")
                    self?.showError("Mac 테스트 알림을 등록하지 못했습니다: \(error.localizedDescription)")
                }
            }
        }
    }

    private func openNotificationSettings() {
        let urls = [
            "x-apple.systempreferences:com.apple.preference.notifications",
            "x-apple.systempreferences:com.apple.Notifications-Settings.extension",
        ]
        for rawURL in urls {
            if let url = URL(string: rawURL), NSWorkspace.shared.open(url) {
                return
            }
        }
        NSWorkspace.shared.open(URL(fileURLWithPath: "/System/Applications/System Settings.app"))
    }

    private func makePairingURL() -> String {
        let host = LocalNetwork.bestIPv4Address() ?? "127.0.0.1"
        return "macdroidnotify://pair?host=\(host)&port=\(port)&token=\(token.base64URLEncodedString())"
    }

    private func makeQRCodeImage(for string: String) -> NSImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(string.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else { return nil }

        let scaled = output.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        let representation = NSCIImageRep(ciImage: scaled)
        let image = NSImage(size: representation.size)
        image.addRepresentation(representation)
        return image
    }

    private func showError(_ message: String) {
        let alert = NSAlert()
        alert.messageText = AppVersion.displayName
        alert.informativeText = message
        alert.addButton(withTitle: "OK")
        alert.runModal()
    }

    private func showInfo(_ message: String) {
        let alert = NSAlert()
        alert.messageText = AppVersion.displayName
        alert.informativeText = message
        alert.addButton(withTitle: "OK")
        alert.runModal()
    }

    private func currentTimeMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}
