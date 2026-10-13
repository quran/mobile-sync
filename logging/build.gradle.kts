plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.vanniktech.maven.publish)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kermit)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.logging)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }

    sourceSets.all {
        languageSettings.optIn("kotlin.experimental.ExperimentalObjCRefinement")
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "logging", version.toString())

    pom {
        name = "Quran.com Mobile Sync Logging"
        description = "Routes Mobile Sync logs, including HTTP calls, to a host app's logger."
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
