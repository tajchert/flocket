// :samples:terminal-playground - SSH-free dev loop for the terminal surface: TerminalSurface + extra keys
// driven by the real Ghostty engine and an in-process fake stream (echo shell, colors, htop-like 60 Hz
// redraw, flood). Target of the terminal macrobenchmarks. May depend on: :core:terminal,
// :core:terminal-ghostty, :core:designsystem, :core:session (contract only).
plugins {
    id("flocket.android.application")
}

android {
    namespace = "com.mtajchert.flocket.samples.terminal"
    defaultConfig {
        applicationId = "com.mtajchert.flocket.samples.terminal"
    }
    // Libraries only have debug/release; the benchmark build types consume their release variant.
    buildTypes {
        named("benchmarkRelease") { matchingFallbacks += "release" }
        named("nonMinifiedRelease") { matchingFallbacks += "release" }
    }
}

dependencies {
    implementation(projects.core.terminal)
    implementation(projects.core.terminalGhostty)
    implementation(projects.core.designsystem)
    implementation(projects.core.session)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.bundles.lifecycle)
    implementation(libs.kotlinx.coroutines.android)
}
