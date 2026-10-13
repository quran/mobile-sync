plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.metro) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
    alias(libs.plugins.native.coroutines) apply false
}

group = libs.versions.project.group.get()
version = providers.gradleProperty("version").orElse("0.0.1-SNAPSHOT").get()

allprojects {
    group = rootProject.group
    version = rootProject.version
}

// GitHub Pages Maven repository: the release workflow passes a checkout of the gh-pages branch
// as githubPagesMavenDir, runs publishToGitHubPages, and pushes the result. Only the
// Android/JVM artifacts go there; iOS ships as the XCFramework on GitHub Releases.
// Maven Central publishing is unchanged and still requires signing.
val githubPagesMavenDir = providers.gradleProperty("githubPagesMavenDir").orNull
val githubPagesPlatformPublications = setOf("android", "jvm")

if (githubPagesMavenDir != null) {
    subprojects {
        plugins.withId("com.vanniktech.maven.publish") {
            extensions.configure<PublishingExtension> {
                repositories {
                    maven {
                        name = "GitHubPages"
                        url = uri(githubPagesMavenDir)
                    }
                }
            }

            // CI has no signing key, and Gradle does not verify signatures from this repository.
            // signAllPublications() marks signing required, so relax it after the module configures.
            afterEvaluate {
                extensions.findByType<SigningExtension>()?.isRequired = false
            }

            tasks.register("publishToGitHubPages") {
                group = "publishing"
                description = "Publishes the Android and JVM publications to the GitHub Pages Maven repository."
                dependsOn(provider {
                    val pagesTasks = tasks.withType<PublishToMavenRepository>().filter { it.repository.name == "GitHubPages" }
                    val platformTasks = pagesTasks.filter { it.publication.name in githubPagesPlatformPublications }
                    // iOS-only modules such as :umbrella have nothing for Android to consume.
                    if (platformTasks.isEmpty()) {
                        emptyList()
                    } else {
                        platformTasks + pagesTasks.filter { it.publication.name == "kotlinMultiplatform" }
                    }
                })
            }
        }
    }
}

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
