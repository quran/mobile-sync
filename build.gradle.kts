plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.native.coroutines) apply false
}

group = "com.quran.shared"
version = "0.0.1-SNAPSHOT"

// Configure test logging - show details only for failures
allprojects {
    tasks.withType<Test> {
        testLogging {
            // Only show output for failed tests, or when requested via --info
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = false
            showCauses = true
            showExceptions = true
            showStackTraces = true
            
            // Show more details when --info flag is used
            if (project.gradle.startParameter.logLevel == LogLevel.INFO) {
                events("started", "passed", "skipped", "failed", "standard_out", "standard_error")
                showStandardStreams = true
            }
        }
    }
}