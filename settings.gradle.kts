pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
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
