import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.vanniktech.maven.publish)
}

kotlin {
    val xcfName = "Shared"
    val xcf = XCFramework(xcfName)

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true

            export(projects.syncengine)
            export(projects.persistence)
            export(projects.syncPipelines)
            export(projects.auth)
            export(projects.demo.common)

            binaryOption("bundleId", "com.quran.sync.$xcfName")
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.syncengine)
            api(projects.persistence)
            api(projects.syncPipelines)
            api(projects.auth)
            api(projects.demo.common)
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
