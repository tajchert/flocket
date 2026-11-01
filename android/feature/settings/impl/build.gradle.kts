// :feature:settings:impl - Screens, ViewModels and navigation entries of the settings feature. Only :app depends on
// it. May depend on: its own :api, other features' :api, :core:* (the feature plugin adds the defaults).
plugins {
    id("flocket.android.feature")
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.session)
    implementation(projects.feature.hosts.api)
    testImplementation(projects.core.testing)
    screenshotTestImplementation(projects.core.testing)
    screenshotTestImplementation(projects.core.designsystem)
    screenshotTestImplementation(projects.core.ui)
}
