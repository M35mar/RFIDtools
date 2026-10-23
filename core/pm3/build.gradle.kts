// pm3-android app module: Compose UI + in-process Proxmark3 client (libpm3android.so).
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Client-only resources (dictionaries, lua scripts, ...) are copied from the
// proxmark3 submodule into the module asset dir at build time: no binary
// duplication in this repository, no fork of the client tree.
//
// dictionaries/extras is left out on purpose: 28 MB of generic password
// brute-force dictionaries, rarely used from a phone. Everything else ships.
// The ".proxmark3" directory name is created at runtime instead, because aapt2
// can drop dot-prefixed components from the asset tree. The asset dir itself
// is a build artifact and stays out of git.
val syncPm3ClientResources by tasks.registering(Sync::class) {
    val client = rootProject.layout.projectDirectory
        .dir("third_party/proxmark3/client")

    from(client.dir("dictionaries")) {
        exclude("extras/**")
        into("dictionaries")
    }
    from(client.dir("lualibs")) {
        into("lualibs")
    }
    from(client.dir("luascripts")) {
        into("luascripts")
    }
    from(client.dir("cmdscripts")) {
        into("cmdscripts")
    }
    from(client.dir("resources")) {
        into("resources")
    }

    into(project.file("src/main/assets"))
}

// Builds the native library with scripts/build-native.sh (two-pass ninja
// build, see the script) and deposits it into jniLibs for packaging.
val localSdkPath: String? =
    rootProject.file("local.properties").takeIf { it.isFile }
        ?.readLines()
        ?.map { it.substringBefore('#').trim() }
        ?.firstOrNull { it.startsWith("sdk.dir") }
        ?.substringAfter('=')
        ?.ifBlank { null }

val buildPm3Native by tasks.registering(Exec::class) {
    description = "Builds libpm3android.so (Proxmark3 client + JNI glue)"
    group = "build"
    commandLine("bash", "${rootDir}/scripts/build-native.sh")
    if (localSdkPath != null) {
        environment("ANDROID_SDK_ROOT", localSdkPath)
    }
}

android {
    namespace = "com.farmacia.pm3android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.farmacia.pm3android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // The only device flavour this project targets.
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

tasks.named("preBuild") {
    dependsOn(buildPm3Native, syncPm3ClientResources)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
}
