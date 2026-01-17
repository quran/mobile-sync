import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// 1. Load the local.properties file
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

// 2. Configure BuildKonfig
buildkonfig {
    packageName = "com.quran.shared.auth"

    defaultConfigs {
        // Read from local.properties, provide a fallback for CI/CD environments
        val clientId = localProperties.getProperty("OAUTH_CLIENT_ID") ?: ""

        buildConfigField(com.codingfeline.buildkonfig.compiler.FieldSpec.Type.STRING, "CLIENT_ID", clientId)
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.maven.publish)
    alias(libs.plugins.buildkonfig)
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
            implementation(libs.oidc.appsupport)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.sha2)
            implementation(libs.multiplatform.settings.no.arg)
            api(libs.androidx.lifecycle.viewmodel) // using `api` for better access from swift code

        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        
        androidMain.dependencies {
             implementation(libs.ktor.client.okhttp)
        }
        
        // No explicit iOS dependencies needed for oidc-appsupport unless specific override
        // But we need a ktor engine for iOS if we pass a client.
        val appleMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
        
        iosX64Main.get().dependsOn(appleMain)
        iosArm64Main.get().dependsOn(appleMain)
        iosSimulatorArm64Main.get().dependsOn(appleMain)
    }
}

android {
    namespace = "com.quran.shared.auth"
    compileSdk = libs.versions.android.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.min.sdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.valueOf("VERSION_${libs.versions.android.java.version.get()}")
        targetCompatibility = JavaVersion.valueOf("VERSION_${libs.versions.android.java.version.get()}")
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(libs.versions.project.group.get(), "auth", libs.versions.project.version.get())

    pom {
        name = "Quran.com Auth Layer"
        description = "A library for authentication with Quran.com"
        inceptionYear = libs.versions.project.inception.year.get()
        url = libs.versions.project.url.get()
    }
}
