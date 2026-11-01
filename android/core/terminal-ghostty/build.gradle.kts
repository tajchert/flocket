// :core:terminal-ghostty - TerminalEngine implementation over libghostty-vt (JNI shim + prebuilt .so).
// May depend on: :core:terminal, :core:common.
// Native libs: scripts/build-ghostty.sh; shim: src/main/cpp. Docs: docs/TERMINAL_ENGINE.md.
plugins {
    id("flocket.android.library")
}

android {
    ndkVersion = "30.0.16248370"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
    sourceSets["androidTest"].assets.directories.add("src/androidTest/assets")
}

dependencies {
    implementation(projects.core.terminal)
    implementation(projects.core.common)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}
