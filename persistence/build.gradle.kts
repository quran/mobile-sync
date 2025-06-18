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
            implementation(libs.kermit)
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
    compileSdk = libs.versions.android.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.min.sdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.valueOf("VERSION_${libs.versions.android.java.version.get()}")
        targetCompatibility = JavaVersion.valueOf("VERSION_${libs.versions.android.java.version.get()}")
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
    coordinates(libs.versions.project.group.get(), "persistence", libs.versions.project.version.get())

    pom {
        name = "Quran.com Persistence Layer"
        description = "A library for sharing data between iOS and Android mobile apps"
        inceptionYear = libs.versions.project.inception.year.get()
        url = libs.versions.project.url.get()
    }
}
