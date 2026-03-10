import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.metro)
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.maven.publish)
    alias(libs.plugins.native.coroutines)
}

kotlin {
    iosX64()
    iosArm64()
    iosSimulatorArm64()


    jvm()
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {

        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kermit)
            implementation(libs.multiplatform.settings.no.arg)
            api(libs.androidx.lifecycle.viewmodel)
            api(projects.syncengine)
            api(projects.persistence)
            api(projects.mutationsDefinitions)
            api(projects.auth)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }

    sourceSets.all {
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

android {
    namespace = "com.quran.shared.sync.pipelines"
    compileSdk = libs.versions.android.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.min.sdk.get().toInt()
        manifestPlaceholders["oidcRedirectScheme"] = "com.quran.oauth"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.valueOf("VERSION_${libs.versions.android.java.version.get()}")
        targetCompatibility = JavaVersion.valueOf("VERSION_${libs.versions.android.java.version.get()}")
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
