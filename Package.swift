// swift-tools-version:5.5
import PackageDescription

// Set to true for local development
let useLocalBuild = false

let package = Package(
    name: "QuranSyncUmbrella",
    platforms: [
        .iOS(.v13)
    ],
    products: [
        .library(
            name: "QuranSyncUmbrella",
            targets: ["QuranSyncUmbrella"]
        )
    ],
    targets: useLocalBuild ? [
        .binaryTarget(
            name: "QuranSyncUmbrella",
            path: "umbrella/build/XCFrameworks/release/QuranSyncUmbrella.xcframework"
        )
    ] : [
        .binaryTarget(
            name: "QuranSyncUmbrella",
            url: "https://github.com/quran/mobile-sync/releases/download/{VERSION}/QuranSyncUmbrella.xcframework.zip",
            checksum: "{CHECKSUM_TO_BE_REPLACED_BY_CI}"
        )
    ]
)
