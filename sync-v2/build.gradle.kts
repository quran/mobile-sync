import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.sqldelight)
}

// The generic, git-like sync engine. It sits alongside `syncengine` and is not wired into QuranDataService yet,
// so `umbrella` doesn't export it.
kotlin {
    applyDefaultHierarchyTemplate()

    iosArm64()
    iosSimulatorArm64()

    jvm()
    android {
        namespace = "com.quran.shared.syncv2"
        compileSdk = libs.versions.android.compile.sdk.get().toInt()
        minSdk = libs.versions.android.min.sdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.kermit)
            implementation(projects.mutationsDefinitions)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        jvmTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.okhttp)
        }

        // The Android host tests run on the JVM, so they use the JDBC SQLite driver, like jvmTest.
        getByName("androidHostTest").dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }

        iosTest.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
    }

    sourceSets.all {
        languageSettings.optIn("kotlin.time.ExperimentalTime")
    }
}

sqldelight {
    databases {
        create("SyncV2Database") {
            packageName.set("com.quran.shared.syncv2.db")
        }
    }
    linkSqlite = true
}
