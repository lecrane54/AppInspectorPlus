// Build-check only: compiles the files you copy into the app repo against android.jar and runs
// JVM tests of the matching/mocking logic. It is not a library to depend on.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(21) }

repositories { mavenCentral() }

val androidJar: File? = run {
    val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: "${System.getProperty("user.home")}/Library/Android/sdk"
    File(sdk, "platforms").listFiles()?.filter { File(it, "android.jar").exists() }
        ?.maxByOrNull { it.name.removePrefix("android-").toIntOrNull() ?: 0 }?.let { File(it, "android.jar") }
}

sourceSets {
    main { kotlin.srcDir("src/debug/java") }
    // The release no-op has the same function name, so it gets its own source set.
    create("release") { kotlin.srcDir("src/release/java") }
}

val okhttp = "com.squareup.okhttp3:okhttp:4.12.0"
dependencies {
    implementation(kotlin("stdlib"))
    implementation(okhttp)
    if (androidJar != null) {
        compileOnly(files(androidJar))
        "releaseCompileOnly"(files(androidJar))
    }
    "releaseImplementation"(kotlin("stdlib"))
    "releaseImplementation"(okhttp)
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303") // android.jar's org.json is stubs only
}

tasks.named("compileKotlin") {
    doFirst { checkNotNull(androidJar) { "Android SDK not found; set ANDROID_HOME to build appium-mocks" } }
}
tasks.named("check") { dependsOn("compileReleaseKotlin") }
