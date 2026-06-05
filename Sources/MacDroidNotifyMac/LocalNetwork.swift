import Foundation

enum LocalNetwork {
    static func bestIPv4Address() -> String? {
        Host.current().addresses.first { address in
            address.contains(".")
                && !address.hasPrefix("127.")
                && !address.hasPrefix("169.254.")
        }
    }
}
