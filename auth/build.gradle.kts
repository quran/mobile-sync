import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// 1. Load the local.properties file
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

// 2. Resolve build type from BuildKonfig flavor only.
// Define the default flavor in gradle.properties and override with -Pbuildkonfig.flavor=release in CI/release.
val supportedBuildTypes = setOf("debug", "release")
val resolvedBuildType = providers.gradleProperty("buildkonfig.flavor").orNull?.lowercase()
    ?: error("Missing 'buildkonfig.flavor'. Set it in gradle.properties or pass -Pbuildkonfig.flavor=debug|release.")

require(resolvedBuildType in supportedBuildTypes) {
    "Unsupported buildkonfig.flavor='$resolvedBuildType'. Supported values: ${supportedBuildTypes.joinToString()}."
}

val isDebugBuild = resolvedBuildType == "debug"

// 2. Configure BuildKonfig
buildkonfig {
    packageName = "com.quran.shared.auth"

    defaultConfigs {
        // Read from local.properties, provide a fallback for CI/CD environments
        val clientId = localProperties.getProperty("OAUTH_CLIENT_ID") ?: ""
        val clientSecret = localProperties.getProperty("OAUTH_CLIENT_SECRET") ?: ""

        buildConfigField(com.codingfeline.buildkonfig.compiler.FieldSpec.Type.STRING, "CLIENT_ID", clientId)
        buildConfigField(com.codingfeline.buildkonfig.compiler.FieldSpec.Type.STRING, "CLIENT_SECRET", clientSecret)
        buildConfigField(com.codingfeline.buildkonfig.compiler.FieldSpec.Type.STRING, "BUILD_TYPE", resolvedBuildType)
        buildConfigField(com.codingfeline.buildkonfig.compiler.FieldSpec.Type.BOOLEAN, "IS_DEBUG", isDebugBuild.toString())
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.metro)
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.maven.publish)
    alias(libs.plugins.buildkonfig)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.native.coroutines)
}

kotlin {
    applyDefaultHierarchyTemplate()

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
            implementation(libs.oidc.appsupport)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.sha2)
            api(libs.multiplatform.settings.no.arg)
            implementation(libs.kermit)
            implementation(libs.kotlinx.serialization.json)
            api(libs.androidx.lifecycle.viewmodel) // using `api` for better access from swift code
            api(projects.mutationsDefinitions)

        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        
        androidMain.dependencies {
             implementation(libs.ktor.client.okhttp)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        
        val iosMain by getting {
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
    }
}

android {
    namespace = "com.quran.shared.auth"
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
    coordinates(libs.versions.project.group.get(), "auth", libs.versions.project.version.get())

    pom {
        name = "Quran.com Auth Layer"
        description = "A library for authentication with Quran.com"
        inceptionYear = libs.versions.project.inception.year.get()
        url = libs.versions.project.url.get()
    }
}
