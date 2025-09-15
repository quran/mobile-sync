plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.vanniktech.maven.publish)
}

kotlin {
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "QuranSync"
            isStatic = true

            export(projects.syncengine)
            export(projects.persistence)
            export(projects.syncPipelines)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.syncengine)
            api(projects.persistence)
            api(projects.syncPipelines)
        }
    }
}

// Task to create XCFramework
tasks.register("createXCFramework") {
    dependsOn("linkReleaseFrameworkIosArm64")
    dependsOn("linkReleaseFrameworkIosSimulatorArm64")
    
    doLast {
        val xcframeworkDir = file("build/XCFrameworks/release")
        xcframeworkDir.mkdirs()
        
        exec {
            commandLine("xcodebuild", "-create-xcframework",
                "-framework", "build/bin/iosArm64/releaseFramework/QuranSyncUmbrella.framework",
                "-framework", "build/bin/iosSimulatorArm64/releaseFramework/QuranSyncUmbrella.framework",
                "-output", "build/XCFrameworks/release/QuranSyncUmbrella.xcframework"
            )
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "syncengine", version.toString())

    pom {
        name = "Quran.com Umbrella Framework"
        description = "An umbrella framework for Quran.com Persistence and SyncEngine"
        inceptionYear = "2025"
        url = "https://github.com/quran/mobile-sync"
    }
}
