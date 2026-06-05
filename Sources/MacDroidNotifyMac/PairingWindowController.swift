import AppKit

final class PairingWindowController: NSWindowController {
    private let titleLabel = NSTextField(labelWithString: "Android QR 페어링")
    private let statusLabel = NSTextField(labelWithString: "")
    private let instructionLabel = NSTextField(wrappingLabelWithString: "Android 앱에서 QR로 페어링 버튼을 누른 뒤 아래 QR을 스캔하세요.")
    private let imageView = NSImageView()
    private let urlField = NSTextField(wrappingLabelWithString: "")

    convenience init() {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 460, height: 560),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        window.title = "MacDroid Notify"
        window.isReleasedWhenClosed = false
        window.center()
        self.init(window: window)
        buildContent()
    }

    func show(pairingURL: String, qrImage: NSImage, status: String) {
        statusLabel.stringValue = "상태: \(status)"
        imageView.image = qrImage
        urlField.stringValue = pairingURL

        NSApp.activate(ignoringOtherApps: true)
        window?.center()
        showWindow(nil)
        window?.makeKeyAndOrderFront(nil)
    }

    private func buildContent() {
        guard let contentView = window?.contentView else { return }

        titleLabel.font = .boldSystemFont(ofSize: 22)
        titleLabel.textColor = .labelColor

        statusLabel.font = .systemFont(ofSize: 15)
        statusLabel.textColor = .secondaryLabelColor

        instructionLabel.font = .systemFont(ofSize: 14)
        instructionLabel.textColor = .secondaryLabelColor
        instructionLabel.maximumNumberOfLines = 2

        imageView.imageScaling = .scaleProportionallyUpOrDown
        imageView.wantsLayer = true
        imageView.layer?.magnificationFilter = .nearest
        imageView.layer?.minificationFilter = .nearest

        urlField.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        urlField.textColor = .secondaryLabelColor
        urlField.maximumNumberOfLines = 4
        urlField.lineBreakMode = .byCharWrapping

        let copyButton = NSButton(title: "URL 복사", target: self, action: #selector(copyURL))
        copyButton.bezelStyle = .rounded

        let closeButton = NSButton(title: "닫기", target: self, action: #selector(closeWindow))
        closeButton.bezelStyle = .rounded

        let buttonStack = NSStackView(views: [copyButton, closeButton])
        buttonStack.orientation = .horizontal
        buttonStack.alignment = .centerY
        buttonStack.distribution = .fillEqually
        buttonStack.spacing = 12

        let stack = NSStackView(views: [
            titleLabel,
            statusLabel,
            instructionLabel,
            imageView,
            urlField,
            buttonStack,
        ])
        stack.orientation = .vertical
        stack.alignment = .centerX
        stack.spacing = 14
        stack.translatesAutoresizingMaskIntoConstraints = false

        contentView.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 28),
            stack.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -28),
            stack.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 26),
            stack.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -24),

            titleLabel.widthAnchor.constraint(equalTo: stack.widthAnchor),
            statusLabel.widthAnchor.constraint(equalTo: stack.widthAnchor),
            instructionLabel.widthAnchor.constraint(equalTo: stack.widthAnchor),
            imageView.widthAnchor.constraint(equalToConstant: 300),
            imageView.heightAnchor.constraint(equalToConstant: 300),
            urlField.widthAnchor.constraint(equalTo: stack.widthAnchor),
            buttonStack.widthAnchor.constraint(equalTo: stack.widthAnchor),
        ])
    }

    @objc private func copyURL() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(urlField.stringValue, forType: .string)
    }

    @objc private func closeWindow() {
        window?.close()
    }
}
