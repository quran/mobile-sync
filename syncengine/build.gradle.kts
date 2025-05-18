import com.vanniktech.maven.publish.SonatypeHost

plugins {
   alias(libs.plugins.kotlin.multiplatform)
   alias(libs.plugins.vanniktech.maven.publish)
}

kotlin {
   jvm()
   iosX64()
   iosArm64()
   iosSimulatorArm64()

   sourceSets {
      val commonMain by getting {
         dependencies {
         }
      }

      val commonTest by getting {
         dependencies {
         }
      }
   }
}

mavenPublishing {
   publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
   signAllPublications()
   coordinates(group.toString(), "syncengine", version.toString())

   pom {
      name = "Quran.com Sync Engine"
      description = "A library for synchronizing data with Quran.com"
      inceptionYear = "2025"
      url = "https://github.com/quran/syncengine"
   }
}
