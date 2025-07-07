import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
   alias(libs.plugins.kotlin.multiplatform)
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
         }
      }

      val commonTest by getting {
         dependencies {
            implementation(libs.kotlin.test)
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
   publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
   signAllPublications()
   coordinates(libs.versions.project.group.get(), "syncengine", libs.versions.project.version.get())

   pom {
      name = "Quran.com Sync Engine"
      description = "A library for synchronizing data with Quran.com"
      inceptionYear = "2025"
      url = "https://github.com/quran/syncengine"
   }
}
