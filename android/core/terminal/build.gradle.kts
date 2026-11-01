// :core:terminal - TerminalEngine interface, FakeTerminalEngine, TerminalController (engine <-> TerminalStream,
// frame coalescing, size ownership) and the terminal surface (View renderer, IME/hardware keyboard, gestures).
// May depend on: :core:session (TerminalStream contract), :core:designsystem, :core:model, :core:common.
// Docs: docs/TERMINAL_RENDERING.md. Fonts: JetBrains Mono (OFL-1.1, third_party/jetbrains-mono).
plugins {
    id("flocket.android.library")
    id("flocket.android.compose")
}

dependencies {
    api(projects.core.session)
    implementation(projects.core.designsystem)
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(projects.core.terminalGhostty)
    // DeviceThroughputTest: SSH + flood fixtures.
    androidTestImplementation(projects.core.ssh)
    androidTestImplementation(testFixtures(projects.core.protocol))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.bundles.testing.android)
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.activity.compose)
}
