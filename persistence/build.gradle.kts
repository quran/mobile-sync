import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.vanniktech.maven.publish)
    alias(libs.plugins.ksp)
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
            implementation(libs.sqldelight.extensions)
            implementation(libs.kermit)
            implementation(libs.kotlinx.datetime)
            api(projects.mutationsDefinitions)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }

        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
        }

        androidUnitTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.sqldelight.jdbc.driver)
        }

        val appleMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.sqldelight.native.driver)
            }
        }

        iosX64Main.get().dependsOn(appleMain)
        iosArm64Main.get().dependsOn(appleMain)
        iosSimulatorArm64Main.get().dependsOn(appleMain)
    }

    sourceSets.all {
        languageSettings.optIn("kotlin.experimental.ExperimentalObjCName")
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
    publishToMavenCentral()
    signAllPublications()
    coordinates(libs.versions.project.group.get(), "persistence", libs.versions.project.version.get())

    pom {
        name = "Quran.com Persistence Layer"
        description = "A library for storing user data for a Quran.com reading app, with the capability of tracking changes."
        inceptionYear = libs.versions.project.inception.year.get()
        url = libs.versions.project.url.get()
    }
}
