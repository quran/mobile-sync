import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
        buildConfigField(com.codingfeline.buildkonfig.compiler.FieldSpec.Type.STRING, "BUILD_TYPE", resolvedBuildType)
        buildConfigField(com.codingfeline.buildkonfig.compiler.FieldSpec.Type.BOOLEAN, "IS_DEBUG", isDebugBuild.toString())
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.metro)
    alias(libs.plugins.android.kotlin.multiplatform.library)
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
    android {
        namespace = "com.quran.shared.auth"
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
            api(libs.oidc.appsupport)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.sha2)
            api(libs.multiplatform.settings)
            api(libs.multiplatform.settings.coroutines)
            api(libs.oidc.tokenstore)
            implementation(libs.kermit)
            implementation(libs.kotlinx.serialization.json)
            api(libs.androidx.lifecycle.viewmodel) // using `api` for better access from swift code
            api(projects.mutationsDefinitions)

        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.multiplatform.settings.test)
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

    sourceSets.all {
        languageSettings.optIn("com.russhwolf.settings.ExperimentalSettingsApi")
        languageSettings.optIn("org.publicvalue.multiplatform.oidc.ExperimentalOpenIdConnect")
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "auth", version.toString())

    pom {
        name = "Quran.com Auth Layer"
        description = "A library for authentication with Quran.com"
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
