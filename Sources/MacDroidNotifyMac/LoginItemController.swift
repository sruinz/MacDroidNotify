import Foundation
import ServiceManagement

final class LoginItemController {
    func enable() throws {
        if #available(macOS 13.0, *) {
            if SMAppService.mainApp.status != .enabled {
                try SMAppService.mainApp.register()
            }
        }
    }

    func disable() throws {
        if #available(macOS 13.0, *) {
            if SMAppService.mainApp.status == .enabled {
                try SMAppService.mainApp.unregister()
            }
        }
    }

    func isEnabled() -> Bool {
        if #available(macOS 13.0, *) {
            return SMAppService.mainApp.status == .enabled
        }
        return false
    }

    func statusDescription() -> String {
        if #available(macOS 13.0, *) {
            switch SMAppService.mainApp.status {
            case .enabled:
                return "켜짐"
            case .notRegistered:
                return "꺼짐"
            case .notFound:
                return "앱 번들을 찾지 못함"
            case .requiresApproval:
                return "사용자 승인 필요"
            @unknown default:
                return "알 수 없음"
            }
        }
        return "지원 안 됨"
    }
}
