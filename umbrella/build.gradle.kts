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
    coordinates(group.toString(), "umbrella", version.toString())

    pom {
        name = "Quran.com Umbrella Framework"
        description = "An umbrella framework for Quran.com Persistence and SyncEngine"
        inceptionYear = libs.versions.project.inception.year.get()
        url = libs.versions.project.url.get()

        licenses {
            license {
                name.set(libs.versions.project.license.name.get())
                url.set(libs.versions.project.license.url.get())
            }
        }
        developers {
            developer {
                id.set(libs.versions.project.developer.id.get())
                name.set(libs.versions.project.developer.name.get())
            }
        }
        scm {
            url.set(libs.versions.project.url.get())
            connection.set(libs.versions.project.scm.connection.get())
            developerConnection.set(libs.versions.project.scm.developer.connection.get())
        }
    }
}
