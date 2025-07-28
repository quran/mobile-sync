import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
   alias(libs.plugins.kotlin.multiplatform)
   alias(libs.plugins.kotlin.serialization)
   alias(libs.plugins.android.library)
   alias(libs.plugins.vanniktech.maven.publish)
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
      val commonMain by getting {
         dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.json)
            implementation(libs.kermit)
         }
      }

      val androidMain by getting {
         dependencies {
            implementation(libs.ktor.client.okhttp)
         }
      }

      val iosX64Main by getting
      val iosArm64Main by getting
      val iosSimulatorArm64Main by getting
      val iosMain by creating {
         dependsOn(commonMain)
         dependencies {
            implementation(libs.ktor.client.darwin)
         }
      }
      iosX64Main.dependsOn(iosMain)
      iosArm64Main.dependsOn(iosMain)
      iosSimulatorArm64Main.dependsOn(iosMain)

      val commonTest by getting {
         dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
         }
      }

      commonMain.dependencies {
         api(projects.mutationsDefinitions)
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

android {
   namespace = "com.quran.shared.syncengine"
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

mavenPublishing {
   publishToMavenCentral()
   signAllPublications()
   coordinates(libs.versions.project.group.get(), "syncengine", libs.versions.project.version.get())

   pom {
      name = "Quran.com Sync Engine"
      description = "A library for synchronizing data with Quran.com"
      inceptionYear = "2025"
      url = "https://github.com/quran/mobile-sync/"
   }
}
