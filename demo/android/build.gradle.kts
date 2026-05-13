import java.util.Properties

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

fun String.toBuildConfigString(): String {
    return "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.metro)
    alias(libs.plugins.kotlin.compose)
}

android {
    compileSdk = 36
    namespace = "com.quran.shared.demo.android"

    defaultConfig {
        minSdk = 23
        targetSdk = 36
        manifestPlaceholders["oidcRedirectScheme"] = "com.quran.oauth"
        manifestPlaceholders["oidcRedirectHost"] = "callback"
        manifestPlaceholders["oidcPostLogoutRedirectScheme"] = "com.quran.oauth"
        manifestPlaceholders["oidcPostLogoutRedirectHost"] = "callback"
        buildConfigField(
            "String",
            "OAUTH_CLIENT_ID",
            (localProperties.getProperty("OAUTH_CLIENT_ID") ?: "").toBuildConfigString()
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(projects.syncengine)
    implementation(projects.persistence)
    implementation(projects.auth)
    implementation(projects.syncPipelines)
    implementation(projects.demo.common)

    // Android Framework & Lifecycle
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.animation)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.runtime)

    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    debugImplementation(libs.compose.ui.tooling)
}
