// :core:testing - Shared test fakes and rules (MainDispatcherRule, fake repositories). Test-only; used via
// testImplementation. May depend on: :core:model, :core:common.
plugins {
    id("flocket.android.library")
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    api(libs.kotlinx.coroutines.test)
    api(libs.junit)
    api(libs.turbine)
}
