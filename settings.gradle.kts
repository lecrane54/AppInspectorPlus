pluginManagement {
    repositories {
        // Central first: the plugin portal's mirror misses some BOMs on a clean (CI) cache.
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "network-rules-sync"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include("plugin")
