// swift-tools-version:5.5
import PackageDescription

// Set to true for local development
let useLocalBuild = false

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
