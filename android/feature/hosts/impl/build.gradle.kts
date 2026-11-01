// :feature:hosts:impl - Screens, ViewModels and navigation entries of the hosts feature. Only :app depends on
// it. May depend on: its own :api, other features' :api, :core:* (the feature plugin adds the defaults).
plugins {
    id("flocket.android.feature")
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.session)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    testImplementation(projects.core.testing)
    screenshotTestImplementation(projects.core.testing)
    screenshotTestImplementation(projects.core.designsystem)
    screenshotTestImplementation(projects.core.ui)
    implementation(projects.feature.onboarding.api)
}
