// swift-tools-version:5.5
import PackageDescription

let package = Package(
    name: "QuranSync",
    platforms: [
        .iOS(.v13)
    ],
    products: [
        .library(
            name: "shared",
            targets: ["shared"]
        )
    ],
    targets: [
        .binaryTarget(
            name: "shared",
            path: "umbrella/build/XCFrameworks/release/Shared.xcframework"
        )
    ]
)
