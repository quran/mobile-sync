plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.vanniktech.maven.publish)
}

kotlin {
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    macosArm64()

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }

    // don't show warnings for expect/actual classes
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.get().compilerOptions {
                freeCompilerArgs.add("-Xexpect-actual-classes")
            }
        }
    }
}

mavenPublishing {
    signAllPublications()
    coordinates(libs.versions.project.group.get(), "mutations-definitions", libs.versions.project.version.get())

    pom {
        name = "Quran.com Mutations Definitions"
        description = "Type declarations that can be used for mutations tracking."
        inceptionYear = "2025"
        url = "https://github.com/quran/mobile-sync"
    }
}

