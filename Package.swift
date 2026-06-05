// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "MacDroidNotify",
    platforms: [
        .macOS(.v15)
    ],
    products: [
        .library(name: "MacDroidNotifyCore", targets: ["MacDroidNotifyCore"]),
        .executable(name: "MacDroidNotifyMac", targets: ["MacDroidNotifyMac"])
    ],
    targets: [
        .target(
            name: "MacDroidNotifyCore",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .executableTarget(
            name: "MacDroidNotifyMac",
            dependencies: ["MacDroidNotifyCore"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(
            name: "MacDroidNotifyCoreTests",
            dependencies: ["MacDroidNotifyCore"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        )
    ]
)
