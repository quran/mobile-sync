import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

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
abstract class CreateXCFrameworkTask : DefaultTask() {
    @get:InputDirectory
    abstract val arm64Framework: DirectoryProperty

    @get:InputDirectory
    abstract val simulatorFramework: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun createXCFramework() {
        val outputFramework = outputDirectory.file("QuranSync.xcframework").get().asFile

        // Clean existing XCFramework if it exists
        if (outputFramework.exists()) {
            outputFramework.deleteRecursively()
        }

        outputDirectory.get().asFile.mkdirs()

        execOperations.exec {
            commandLine("xcodebuild", "-create-xcframework",
                "-framework", arm64Framework.get().asFile.absolutePath,
                "-framework", simulatorFramework.get().asFile.absolutePath,
                "-output", outputFramework.absolutePath
            )
        }
    }
}

tasks.register<CreateXCFrameworkTask>("createXCFramework") {
    dependsOn("linkReleaseFrameworkIosArm64")
    dependsOn("linkReleaseFrameworkIosSimulatorArm64")

    arm64Framework.set(layout.buildDirectory.dir("bin/iosArm64/releaseFramework/QuranSync.framework"))
    simulatorFramework.set(layout.buildDirectory.dir("bin/iosSimulatorArm64/releaseFramework/QuranSync.framework"))
    outputDirectory.set(layout.buildDirectory.dir("XCFrameworks/release"))
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
