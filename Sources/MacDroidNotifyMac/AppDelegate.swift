import AppKit
import CoreImage
import Foundation
import MacDroidNotifyCore

final class AppDelegate: NSObject, NSApplicationDelegate, TcpNotificationServerDelegate {
    private let legacyDefaultsMigrator = LegacyDefaultsMigrator()
    private let tokenStore = TokenStore()
    private let macIdentityStore = MacIdentityStore()
    private let tlsIdentityStore = TLSIdentityStore()
    private let portStore = PortStore()
    private let loginItemController = LoginItemController()
    private let debugLogStore = MacDebugLogStore()
    private let presenter = NotificationPresenter()
    private let deduplicator = NotificationDeduplicator(windowSeconds: 30)

    private var port: UInt16 = NetworkPort.defaultValue
    private var token = Data()
    private var macId = ""
    private var tlsIdentity: TLSIdentity?
    private var server: TcpNotificationServer?
    private var menu: StatusMenuController?
    private var pairingWindowController: PairingWindowController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        debugLogStore.append("app launch")
        legacyDefaultsMigrator.migrateIfNeeded()
        token = tokenStore.loadOrCreateToken()
        macId = macIdentityStore.loadOrCreateMacId()
        port = portStore.loadPort()
        installApplicationIcon()
        presenter.requestAuthorization()
        do {
            tlsIdentity = try tlsIdentityStore.loadOrCreateIdentity(macId: macId)
            debugLogStore.append("tls identity ready fingerprint=\(tlsIdentity?.fingerprint ?? "")")
        } catch {
            debugLogStore.append("tls identity failed \(error.localizedDescription)")
            showError("TLS 인증서를 준비하지 못했습니다: \(error.localizedDescription)")
        }

        let menu = StatusMenuController()
        menu.onShowPairing = { [weak self] in self?.showPairingInfo() }
        menu.onSendClipboard = { [weak self] in self?.sendMacClipboardToAndroid() }
        menu.onCheckNotifications = { [weak self] in self?.showNotificationStatus() }
        menu.onSendTestNotification = { [weak self] in self?.sendMacTestNotification() }
        menu.onChangePort = { [weak self] in self?.showPortEditor() }
        menu.onToggleLoginItem = { [weak self] in self?.toggleLoginItem() }
        menu.onCopyDebugLog = { [weak self] in self?.copyDebugLog() }
        menu.updateLoginItem(enabled: loginItemController.isEnabled())
        self.menu = menu

        startServer(port: port)
    }

    private func startServer(port: UInt16) {
        server?.stop()
        guard let tlsIdentity else {
            menu?.updateStatus("TLS 준비 실패")
            return
        }

        let server = TcpNotificationServer(
            port: port,
            token: token,
            tlsIdentity: tlsIdentity.secIdentity,
            bonjourRecord: BonjourTXTRecord(macId: macId, tlsFingerprint: tlsIdentity.fingerprint)
        )
        server.delegate = self
        self.server = server

        do {
            try server.start()
            menu?.updateStatus("대기 중 :\(port)")
            debugLogStore.append("server started port=\(port)")
        } catch {
            menu?.updateStatus("서버 시작 실패 :\(port)")
            debugLogStore.append("server start failed \(error.localizedDescription)")
            showError("리스너를 시작하지 못했습니다: \(error.localizedDescription)")
        }
    }

    func serverDidUpdateStatus(_ status: String) {
        debugLogStore.append("server status \(status)")
        DispatchQueue.main.async { [weak self] in
            self?.menu?.updateStatus(status)
        }
    }

    func serverDidReceiveNotification(_ payload: NotificationPayload) {
        debugLogStore.append("notification received app=\(payload.appName) id=\(payload.id)")
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
        debugLogStore.append("clipboard received textLen=\(text.count)")
        DispatchQueue.main.async { [weak self] in
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(text, forType: .string)
            self?.menu?.updateStatus("클립보드 수신됨")
        }
    }

    func serverDidAuthenticateDevice(deviceName: String) {
        debugLogStore.append("device authenticated name=\(deviceName)")
        DispatchQueue.main.async { [weak self] in
            self?.enableLoginItemAfterPairing()
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
                로그인 자동 실행: \(self.loginItemController.statusDescription())
                TLS fingerprint: \(self.tlsIdentity?.fingerprint ?? "없음")
                \(self.iconDiagnostics())
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

    private func enableLoginItemAfterPairing() {
        guard !loginItemController.isEnabled() else { return }
        do {
            try loginItemController.enable()
            menu?.updateLoginItem(enabled: true)
            debugLogStore.append("login item enabled after pairing")
        } catch {
            debugLogStore.append("login item enable failed \(error.localizedDescription)")
        }
    }

    private func toggleLoginItem() {
        do {
            if loginItemController.isEnabled() {
                try loginItemController.disable()
            } else {
                try loginItemController.enable()
            }
            menu?.updateLoginItem(enabled: loginItemController.isEnabled())
            menu?.updateStatus("로그인 자동 실행: \(loginItemController.statusDescription())")
            debugLogStore.append("login item toggled status=\(loginItemController.statusDescription())")
        } catch {
            debugLogStore.append("login item toggle failed \(error.localizedDescription)")
            showError("로그인 자동 실행 설정을 바꾸지 못했습니다: \(error.localizedDescription)")
        }
    }

    private func copyDebugLog() {
        let report = debugLogStore.report(
            status: menu?.currentStatus ?? "알 수 없음",
            port: port,
            macId: macId,
            tlsFingerprint: tlsIdentity?.fingerprint ?? "없음",
            loginStatus: loginItemController.statusDescription(),
            iconDiagnostics: iconDiagnostics()
        )
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(report, forType: .string)
        menu?.updateStatus("디버그 로그 복사됨")
    }

    private func iconDiagnostics() -> String {
        let bundle = Bundle.main
        let iconFile = bundle.object(forInfoDictionaryKey: "CFBundleIconFile") as? String ?? "없음"
        let iconName = bundle.object(forInfoDictionaryKey: "CFBundleIconName") as? String ?? "없음"
        let iconPath = iconResourcePath()
        let assetsCarPath = bundle.path(forResource: "Assets", ofType: "car")
        let appIconSize = NSApplication.shared.applicationIconImage.map { image in
            "\(Int(image.size.width))x\(Int(image.size.height))"
        } ?? "없음"
        let bundled = bundle.bundleURL.pathExtension == "app"
        return """
        앱 번들 실행: \(bundled ? "예" : "아니오")
        CFBundleIconFile: \(iconFile)
        CFBundleIconName: \(iconName)
        아이콘 리소스: \(iconPath ?? "없음")
        아이콘 리소스 존재: \(iconPath.map { FileManager.default.fileExists(atPath: $0) } == true ? "예" : "아니오")
        Assets.car: \(assetsCarPath ?? "없음")
        Assets.car 존재: \(assetsCarPath.map { FileManager.default.fileExists(atPath: $0) } == true ? "예" : "아니오")
        앱 아이콘 이미지 크기: \(appIconSize)
        알림 아이콘 참고: swift run처럼 .app 밖에서 실행하면 macOS 알림 아이콘이 비어 보일 수 있습니다.
        """
    }

    private func installApplicationIcon() {
        guard let iconPath = iconResourcePath(),
              let image = NSImage(contentsOfFile: iconPath) else {
            debugLogStore.append("app icon load failed")
            return
        }
        NSApplication.shared.applicationIconImage = image
        debugLogStore.append("app icon loaded path=\(iconPath) size=\(Int(image.size.width))x\(Int(image.size.height))")
    }

    private func iconResourcePath() -> String? {
        let bundle = Bundle.main
        let iconFile = bundle.object(forInfoDictionaryKey: "CFBundleIconFile") as? String
        let iconName = bundle.object(forInfoDictionaryKey: "CFBundleIconName") as? String
        let candidates = [iconFile, iconName, "MacDroidNotify"]
            .compactMap { $0 }
            .flatMap { value -> [String] in
                let name = (value as NSString).deletingPathExtension
                return [value, name]
            }

        for candidate in candidates {
            let name = (candidate as NSString).deletingPathExtension
            if let path = bundle.path(forResource: name, ofType: "icns") {
                return path
            }
        }
        return nil
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
        let payload = SecurePairingPayload(
            host: host,
            port: port,
            token: token.base64URLEncodedString(),
            macId: macId,
            tlsFingerprint: tlsIdentity?.fingerprint ?? ""
        )
        return payload.urlString
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
