// swift-tools-version:5.5
import PackageDescription

// Check environment variable for local development mode (defaults to false)
let useLocalBuild = ProcessInfo.processInfo.environment["QURAN_SYNC_LOCAL_BUILD"] == "true"

let package = Package(
    name: "QuranSync",
    platforms: [
        .iOS(.v13)
    ],
    products: [
        .library(
            name: "QuranSync",
            targets: ["QuranSync"]
        )
    ],
    targets: useLocalBuild ? [
        .binaryTarget(
            name: "QuranSync",
            path: "umbrella/build/XCFrameworks/release/QuranSync.xcframework"
        )
    ] : [
        .binaryTarget(
            name: "QuranSync",
            url: "https://github.com/quran/mobile-sync/releases/download/{VERSION}/QuranSync.xcframework.zip",
            checksum: "{CHECKSUM_TO_BE_REPLACED_BY_CI}"
        )
    ]
)
