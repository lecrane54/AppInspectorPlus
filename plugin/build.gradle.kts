plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = "io.github.networkrules"
version = providers.gradleProperty("pluginVersion").get()

kotlin { jvmToolchain(21) }

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // Compile against a local Android Studio when one is installed (fast, no download);
        // otherwise (e.g. CI) download the release named by androidStudioVersion.
        val localStudio = providers.gradleProperty("androidStudioPath").orNull?.takeIf { file(it).exists() }
        if (localStudio != null) {
            local(localStudio)
        } else {
            androidStudio(providers.gradleProperty("androidStudioVersion"))
        }
        bundledPlugin("org.jetbrains.android")
        bundledPlugin("com.intellij.modules.json")
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    projectName = "network-rules-sync"
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
}
