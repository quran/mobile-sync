plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
}

group = "com.quran.shared"
version = "0.0.1-SNAPSHOT"