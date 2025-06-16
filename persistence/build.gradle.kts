import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.vanniktech.maven.publish)
}

kotlin {
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    androidTarget {
        publishLibraryVariants("release")
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.sqldelight.extensions)
            implementation("co.touchlab:kermit:2.0.0")
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.sqldelight.sqlite.driver)
            implementation("app.cash.sqldelight:jdbc-driver")
        }

        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
        }

        nativeMain.dependencies {
            implementation(libs.sqldelight.native.driver)
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

android {
    namespace = "com.quran.shared.persistence"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

sqldelight {
    databases {
        create("QuranDatabase", Action {
            packageName.set("com.quran.shared.persistence")
        })
    }
    linkSqlite = true
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates(group.toString(), "persistence", version.toString())

    pom {
        name = "Quran.com Persistence Layer"
        description = "A library for sharing data between iOS and Android mobile apps"
        inceptionYear = "2025"
        url = "https://github.com/quran/mobile-data"
    }
}
