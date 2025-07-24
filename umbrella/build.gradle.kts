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
            baseName = "Shared"
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

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "syncengine", version.toString())

    pom {
        name = "Quran.com Umbrella Framework"
        description = "An umbrella framework for Quran.com Persistence and SyncEngine"
        inceptionYear = "2025"
        url = "https://github.com/quran/syncengine"
    }
}