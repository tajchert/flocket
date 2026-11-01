// :core:ui - Shared UI built on the design system (AppError -> message mapping, status badges, ...).
// May depend on: :core:designsystem, :core:model, :core:common.
plugins {
    id("flocket.android.library")
    id("flocket.android.compose")
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.model)
    implementation(projects.core.common)
}
