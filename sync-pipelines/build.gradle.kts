import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.metro)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.maven.publish)
    alias(libs.plugins.native.coroutines)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()


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
            implementation(libs.androidx.datastore)
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.coroutines)
            implementation(libs.multiplatform.settings.datastore)
            implementation(libs.oidc.tokenstore)
            api(libs.androidx.lifecycle.viewmodel)
            api(projects.syncengine)
            api(projects.persistence)
            api(projects.mutationsDefinitions)
            api(projects.auth)
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
        description = "Top-level mobile sync artifact integrating auth, persistence, and syncengine"
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

// The sync integration harness (jvmTest) shortens scheduler timings through an internal syncengine
// seam, so the JVM test compilation is a friend of syncengine's JVM jar. Main code still sees only
// syncengine's public API.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlinJvm") {
    val syncengineJvmJar = project(":syncengine").layout.buildDirectory
        .file("libs/syncengine-jvm-${project.version}.jar")
    compilerOptions.freeCompilerArgs.addAll(
        syncengineJvmJar.map { listOf("-Xfriend-paths=${it.asFile.absolutePath}") }
    )
}
