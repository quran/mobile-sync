import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.metro)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.maven.publish)
    alias(libs.plugins.native.coroutines)
}

kotlin {
    // The common tests open an in-memory SQLite database, so the iOS test binaries link SQLite.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.all { linkerOpts("-lsqlite3") }
    }


    jvm()
    android {
        namespace = "com.quran.shared.sync.pipelines"
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
            implementation(libs.kotlinx.datetime)
            implementation(libs.kermit)
            implementation(libs.sqldelight.extensions)
            implementation(libs.androidx.datastore)
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.coroutines)
            implementation(libs.multiplatform.settings.datastore)
            implementation(libs.oidc.tokenstore)
            api(libs.androidx.lifecycle.viewmodel)
            api(projects.persistence)
            api(projects.mutationsDefinitions)
            api(projects.auth)
            implementation(projects.syncV2)
            implementation(projects.logging)
            implementation(libs.ktor.client.core)
        }

        // The sync client's HTTP engine.
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.multiplatform.settings.test)
        }

        jvmTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.kotlinx.serialization.json)
        }

        // The common tests open an in-memory database on each platform's own SQLite driver.
        getByName("androidHostTest").dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }

        iosTest.dependencies {
            implementation(libs.sqldelight.native.driver)
        }

    }

    sourceSets.all {
        languageSettings.optIn("com.russhwolf.settings.ExperimentalSettingsApi")
        languageSettings.optIn("com.russhwolf.settings.ExperimentalSettingsImplementation")
        languageSettings.optIn("org.publicvalue.multiplatform.oidc.ExperimentalOpenIdConnect")
        languageSettings.optIn("kotlin.experimental.ExperimentalObjCName")
        languageSettings.optIn("kotlin.experimental.ExperimentalObjCRefinement")
        languageSettings.optIn("kotlin.time.ExperimentalTime")
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
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "mobile-sync", version.toString())

    pom {
        name = "Quran.com Mobile Sync"
        description = "Top-level mobile sync artifact integrating auth, persistence, and the sync engine"
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
