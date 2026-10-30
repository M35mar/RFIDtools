// pm3-android app module: Compose UI + in-process Proxmark3 client (libpm3android.so).
// SPDX-License-Identifier: GPL-3.0-or-later

import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

// Reproducible reference to the embedded client and to this app revision.
// These values are shown in the Info screen and in diagnostics.
fun gitCommit(path: java.io.File): String = try {
    providers.exec {
        commandLine("git", "-C", path.absolutePath, "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifBlank { "unknown" }
} catch (_: Exception) {
    "unknown"
}

val appCommit = gitCommit(rootProject.projectDir)
val pm3Commit = gitCommit(rootProject.file("third_party/proxmark3"))

// Optional release signing: create keystore.properties from the example file.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
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
val syncPm3ClientResources = tasks.register<Sync>("syncPm3ClientResources") {
    val client = rootProject.layout.projectDirectory
        .dir("third_party/proxmark3/client")

    // Upstream occasionally adopts one of our lua-extra tools: the local copy
    // must still win, so keep both and let the later `from` overwrite.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    from(client.dir("dictionaries")) {
        exclude("extras/**")
        into("dictionaries")
    }
    from(client.dir("lualibs")) {
        into("lualibs")
    }
    // Our own Lua tools are merged into the client's luascripts directory.
    from(client.dir("luascripts")) {
        into("luascripts")
    }
    from(project.file("src/main/lua-extra")) {
        into("luascripts")
    }
    from(client.dir("cmdscripts")) {
        into("cmdscripts")
    }
    // Python scripts are executed by the embedded Chaquopy runtime. The
    // upstream SWIG wrapper is replaced by our Kotlin-bridge shim, so the
    // generated pm3.py must not shadow it.
    from(client.dir("pyscripts")) {
        exclude("pm3.py")
        into("pyscripts")
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
        ?.replace("\\:", ":")
        ?.replace("\\\\", "/")
        ?.replace("\\", "/")
        ?.ifBlank { null }

val buildPm3Native = tasks.register<Exec>("buildPm3Native") {
    description = "Builds libpm3android.so (Proxmark3 client + JNI glue)"
    group = "build"
    commandLine("bash", "${rootDir}/scripts/build-native.sh")
    if (localSdkPath != null) {
        environment("ANDROID_SDK_ROOT", localSdkPath)
    }
    inputs.dir(rootProject.file("third_party/proxmark3/client"))
    inputs.dir(project.file("src/main/cpp"))
    inputs.files(
        rootProject.file("scripts/build-native.sh"),
        rootProject.file("scripts/prepare-pm3.sh"),
        project.file("src/main/lua-extra"),
    )
    outputs.file(project.file("src/main/jniLibs/arm64-v8a/libpm3android.so"))
}

android {
    namespace = "com.farmacia.pm3android"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.farmacia.pm3android"
        minSdk = 26
        targetSdk = 37
        versionCode = 14
        versionName = "1.0.3"

        ndk {
            // The only device flavour this project targets.
            abiFilters.add("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "APP_GIT_COMMIT", "\"$appCommit\"")
        buildConfigField("String", "PM3_CLIENT_COMMIT", "\"$pm3Commit\"")
    }

    signingConfigs {
        if (keystorePropertiesFile.isFile) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            if (keystorePropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/LICENSE.md",
            "META-INF/LICENSE-notice.md",
        )
    }
}

chaquopy {
    defaultConfig {
        version = "3.14"
    }
}

tasks.named("preBuild") {
    dependsOn(buildPm3Native, syncPm3ClientResources)
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
