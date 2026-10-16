import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.vanniktech.maven.publish)
}

// The generic, git-like sync engine. Only `sync-pipelines` uses it, so `umbrella` doesn't export it. It is still
// published, because the published `persistence` and `mobile-sync` artifacts depend on it.
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
            // State is a JsonObject, and SyncApi takes a Ktor HttpClient: both are part of the public API.
            api(libs.kotlinx.serialization.json)
            api(libs.ktor.client.core)
            implementation(libs.kermit)
            implementation(projects.mutationsDefinitions)
            // logSummary: failures are logged without their messages.
            implementation(projects.logging)
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

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "sync-v2", version.toString())

    pom {
        name = "Quran.com Sync Engine"
        description = "The sync engine for Quran.com user data: fetch, three-way merge and push, with its synced tables."
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
