// :core:ui - Shared UI built on the design system: AppError -> UiError mapping, status components, trust
// dialogs, the host form and the navigation contracts (Navigator, EntryProviderInstaller).
// May depend on: :core:designsystem, :core:terminal (settings preview), :core:model, :core:common, :core:data (contracts only).
plugins {
    id("flocket.android.library")
    id("flocket.android.compose")
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.terminal)
    api(projects.core.model)
    api(projects.core.common)
    api(projects.core.data)
    api(libs.androidx.navigation3.runtime)

    testImplementation(projects.core.testing)
    screenshotTestImplementation(projects.core.testing)
    screenshotTestImplementation(projects.core.designsystem)
}
