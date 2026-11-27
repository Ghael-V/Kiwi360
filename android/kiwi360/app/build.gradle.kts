// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

// Kiwi360 prototype: the Compose game library (dev.kiwi360.frontend) plus the
// prebuilt Xenia Edge libxenia-app.so (built by CMake in ../build-edge-android),
// which runs in its own process so closing a game returns to the library.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    // The JNI symbols in libxenia-app.so are Java_jp_xenia_emulator_*.
    namespace = "jp.xenia.emulator"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.kiwi360"
        minSdk = 30
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
        // Only debug builds let other apps (adb) start a game directly.
        manifestPlaceholders["emulatorExported"] = "false"
        // The recommended settings server; -PaiConfigUrl=http://... points a test build at a
        // mock, and only then (or with an http -PupdateUrl) is cleartext allowed.
        val aiConfigUrl = (project.findProperty("aiConfigUrl") as String?) ?: "https://ai.lemon-emu.org/v1"
        buildConfigField("String", "AI_CONFIG_URL", "\"$aiConfigUrl\"")
        // The in-app updater asks the project's releases (Updater.kt); -PupdateUrl=http://...
        // replaces them with one releases list from a mock, and then debug builds check too.
        val updateUrl = (project.findProperty("updateUrl") as String?).orEmpty()
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
        manifestPlaceholders["cleartextAllowed"] =
            (aiConfigUrl.startsWith("http://") || updateUrl.startsWith("http://")).toString()
    }

    buildTypes {
        getByName("debug") {
            // The development build, installed next to the release one.
            applicationIdSuffix = ".proto"
            versionNameSuffix = "-proto"
            isDebuggable = true
            isJniDebuggable = true
            manifestPlaceholders["emulatorExported"] = "true"
        }
        getByName("release") {
            // Signed afterwards by tools/build_release.ps1 with the release key.
            isMinifyEnabled = false
            isDebuggable = false
            isJniDebuggable = false
        }
    }

    lint {
        // False positive here: lint reports both activities as not extending
        // Activity (they do; the debug build runs them).
        disable += "Instantiatable"
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
        buildConfig = true
    }

    packaging {
        // Extracted to nativeLibraryDir: libadrenotools loads its hook
        // libraries from there to load custom Vulkan drivers (Turnip).
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
}
