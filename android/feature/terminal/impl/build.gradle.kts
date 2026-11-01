// :feature:terminal:impl - The interactive terminal screen (TerminalRoute): ViewModel owning one TerminalController,
// toolbar actions, end-state banners. Only :app depends on it. May depend on: its own :api, other features' :api,
// :core:* (the feature plugin adds the defaults).
plugins {
    id("flocket.android.feature")
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.session)
    implementation(projects.core.terminal)
    implementation(projects.core.terminalGhostty)

    screenshotTestImplementation(projects.core.testing)
    screenshotTestImplementation(projects.core.designsystem)
    screenshotTestImplementation(projects.core.ui)

    androidTestImplementation(projects.core.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.bundles.testing.android)
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.activity.compose)
}
