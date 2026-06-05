import Foundation
import MacDroidNotifyCore
import Network
import Security

protocol TcpNotificationServerDelegate: AnyObject {
    func serverDidUpdateStatus(_ status: String)
    func serverDidReceiveNotification(_ payload: NotificationPayload)
    func serverDidReceiveClipboardText(_ text: String)
    func serverDidAuthenticateDevice(deviceName: String)
}

final class TcpNotificationServer {
    weak var delegate: TcpNotificationServerDelegate?

    private let port: UInt16
    private let token: Data
    private let tlsIdentity: SecIdentity
    private let bonjourRecord: BonjourTXTRecord
    private let queue = DispatchQueue(label: "dev.svrx.macdroidnotify.server")
    private var listener: NWListener?
    private var activeSession: ClientSession?

    init(port: UInt16, token: Data, tlsIdentity: SecIdentity, bonjourRecord: BonjourTXTRecord) {
        self.port = port
        self.token = token
        self.tlsIdentity = tlsIdentity
        self.bonjourRecord = bonjourRecord
    }

    func start() throws {
        let port = NWEndpoint.Port(rawValue: port)!
        let tlsOptions = NWProtocolTLS.Options()
        guard let securityIdentity = sec_identity_create(tlsIdentity) else {
            throw TcpNotificationServerError.tlsIdentityUnavailable
        }
        sec_protocol_options_set_local_identity(tlsOptions.securityProtocolOptions, securityIdentity)

        let tcpOptions = NWProtocolTCP.Options()
        let parameters = NWParameters(tls: tlsOptions, tcp: tcpOptions)
        let listener = try NWListener(using: parameters, on: port)
        listener.service = NWListener.Service(
            name: Host.current().localizedName ?? "MacDroid Notify",
            type: "_macdroidnotify._tcp",
            txtRecord: NWTXTRecord(bonjourRecord.dictionary)
        )
        listener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }
        listener.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.delegate?.serverDidUpdateStatus("대기 중 :\(self?.port ?? 0)")
            case let .failed(error):
                self?.delegate?.serverDidUpdateStatus("리스너 실패: \(error)")
            default:
                break
            }
        }
        self.listener = listener
        listener.start(queue: queue)
    }

    func sendClipboardToAndroid(_ payload: ClipboardPayload) throws {
        guard let activeSession else {
            throw TcpNotificationServerError.notConnected
        }
        try activeSession.send(.clipboardToAndroid(payload))
    }

    func stop() {
        activeSession?.cancel()
        activeSession = nil
        listener?.cancel()
        listener = nil
    }

    private func accept(_ connection: NWConnection) {
        activeSession?.cancel()
        let session = ClientSession(connection: connection, token: token)
        session.onStatus = { [weak self] status in
            self?.delegate?.serverDidUpdateStatus(status)
        }
        session.onNotification = { [weak self] payload in
            self?.delegate?.serverDidReceiveNotification(payload)
        }
        session.onClipboard = { [weak self] text in
            self?.delegate?.serverDidReceiveClipboardText(text)
        }
        session.onAuthenticated = { [weak self] deviceName in
            self?.delegate?.serverDidAuthenticateDevice(deviceName: deviceName)
        }
        activeSession = session
        session.start(queue: queue)
    }
}

enum TcpNotificationServerError: LocalizedError {
    case notConnected
    case tlsIdentityUnavailable

    var errorDescription: String? {
        switch self {
        case .notConnected:
            return "Android가 Mac에 연결되어 있지 않습니다."
        case .tlsIdentityUnavailable:
            return "TLS identity를 Network.framework에 전달하지 못했습니다."
        }
    }
}

private final class ClientSession {
    var onStatus: ((String) -> Void)?
    var onNotification: ((NotificationPayload) -> Void)?
    var onClipboard: ((String) -> Void)?
    var onAuthenticated: ((String) -> Void)?

    private let connection: NWConnection
    private let token: Data
    private let nonce = RandomToken.nonce()
    private var authenticated = false
    private var buffer = Data()

    init(connection: NWConnection, token: Data) {
        self.connection = connection
        self.token = token
    }

    func start(queue: DispatchQueue) {
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.onStatus?("Android 연결됨")
                try? self?.send(.challenge(ChallengePayload(nonce: self?.nonce ?? "")))
                self?.receiveNext()
            case .cancelled:
                self?.onStatus?("Android 연결 끊김")
            case let .failed(error):
                self?.onStatus?("연결 실패: \(error)")
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    func cancel() {
        connection.cancel()
    }

    func send(_ message: WireMessage) throws {
        let line = try NDJSONCodec.encode(message)
        guard let data = line.data(using: .utf8) else {
            throw ProtocolError.invalidLine
        }
        connection.send(content: data, completion: .contentProcessed { _ in })
    }

    private func receiveNext() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                buffer.append(data)
                processBufferedLines()
            }
            if isComplete || error != nil {
                connection.cancel()
                return
            }
            receiveNext()
        }
    }

    private func processBufferedLines() {
        while let newline = buffer.firstIndex(of: 0x0A) {
            let lineData = buffer[..<newline]
            buffer.removeSubrange(...newline)
            guard let line = String(data: lineData, encoding: .utf8), !line.isEmpty else {
                continue
            }
            handle(line)
        }
    }

    private func handle(_ line: String) {
        do {
            let message = try NDJSONCodec.decode(line)
            if !authenticated {
                try authenticate(message)
                return
            }
            try handleAuthenticated(message)
        } catch {
            onStatus?("프로토콜 오류")
            connection.cancel()
        }
    }

    private func authenticate(_ message: WireMessage) throws {
        guard case let .hello(payload) = message,
              payload.protocolVersion == ProtocolLimits.version,
              PairingAuth.verify(token: token, nonce: nonce, auth: payload.auth) else {
            throw ProtocolError.authenticationFailed
        }
        authenticated = true
        onStatus?("Android 연결됨: \(payload.deviceName)")
        onAuthenticated?(payload.deviceName)
        try send(.pairingAccepted(PairingAcceptedPayload(
            macName: Host.current().localizedName ?? "Mac",
            timestampMillis: currentTimeMillis()
        )))
    }

    private func handleAuthenticated(_ message: WireMessage) throws {
        switch message {
        case let .notificationPosted(payload):
            onNotification?(payload.limited())
        case let .clipboardToMac(payload):
            onClipboard?(try payload.validated().text)
        case let .ping(payload):
            try send(.pong(PongPayload(id: payload.id, timestampMillis: currentTimeMillis())))
        default:
            break
        }
    }

    private func currentTimeMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}
