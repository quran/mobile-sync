pluginManagement {
   repositories {
      mavenCentral()
      gradlePluginPortal()
      google()
   }
}

dependencyResolutionManagement {
   @Suppress("UnstableApiUsage")
   repositories {
      mavenCentral()
      google()
   }
}

rootProject.name = "quransync"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":syncengine")
include(":persistence")
include(":umbrella")
include(":demo:android")
include(":mutations-definitions")