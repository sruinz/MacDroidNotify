import Foundation
import MacDroidNotifyCore
import UserNotifications

final class NotificationPresenter: NSObject, UNUserNotificationCenterDelegate {
    private static let androidThreadIdentifier = "dev.svrx.macdroidnotify.android"

    private let center = UNUserNotificationCenter.current()

    override init() {
        super.init()
        center.delegate = self
    }

    func requestAuthorization() {
        center.requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    func present(payload: NotificationPayload, completion: ((Result<Void, Error>) -> Void)? = nil) {
        let content = UNMutableNotificationContent()
        content.title = payload.title.isEmpty ? payload.appName : payload.title
        content.subtitle = payload.appName
        content.body = payload.text
        content.sound = .default
        content.threadIdentifier = Self.androidThreadIdentifier

        let request = UNNotificationRequest(
            identifier: "android-\(payload.id)",
            content: content,
            trigger: nil
        )
        center.add(request) { error in
            if let error {
                completion?(.failure(error))
            } else {
                completion?(.success(()))
            }
        }
    }

    func presentLocalTest(completion: ((Result<Void, Error>) -> Void)? = nil) {
        present(
            payload: NotificationPayload(
                id: "mac-test-\(Int(Date().timeIntervalSince1970 * 1000))",
                packageName: "dev.svrx.macdroidnotify.mac",
                appName: "MacDroid Notify",
                title: "Mac 알림 테스트",
                text: "MacDroid Notify가 macOS 알림을 표시할 수 있는지 확인합니다.",
                timestampMillis: Int64(Date().timeIntervalSince1970 * 1000)
            ),
            completion: completion
        )
    }

    func notificationStatus(completion: @escaping (String) -> Void) {
        center.getNotificationSettings { settings in
            let lines = [
                "권한: \(settings.authorizationStatus.koreanDescription)",
                "알림 표시: \(settings.alertSetting.koreanDescription)",
                "소리: \(settings.soundSetting.koreanDescription)",
                "배지: \(settings.badgeSetting.koreanDescription)",
                "알림 센터: \(settings.notificationCenterSetting.koreanDescription)",
                "잠금 화면: \(settings.lockScreenSetting.koreanDescription)",
            ]
            completion(lines.joined(separator: "\n"))
        }
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }
}

private extension UNAuthorizationStatus {
    var koreanDescription: String {
        switch self {
        case .notDetermined:
            return "아직 요청 안 됨"
        case .denied:
            return "거부됨"
        case .authorized:
            return "허용됨"
        case .provisional:
            return "임시 허용"
        case .ephemeral:
            return "임시 세션 허용"
        @unknown default:
            return "알 수 없음"
        }
    }
}

private extension UNNotificationSetting {
    var koreanDescription: String {
        switch self {
        case .notSupported:
            return "지원 안 됨"
        case .disabled:
            return "꺼짐"
        case .enabled:
            return "켜짐"
        @unknown default:
            return "알 수 없음"
        }
    }
}
