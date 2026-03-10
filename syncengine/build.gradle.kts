plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.vanniktech.maven.publish)
}

kotlin {
    applyDefaultHierarchyTemplate()

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    jvm()

    sourceSets {

        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.json)
            implementation(libs.kermit)
            api(projects.mutationsDefinitions)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }

        val iosMain by getting {
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
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

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "syncengine", version.toString())

    pom {
        name = "Quran.com Sync Engine"
        description = "A library for synchronizing data with Quran.com"
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
