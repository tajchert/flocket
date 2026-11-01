// :core:testing - Shared test fakes, SampleData and rules (MainDispatcherRule, fake repositories). Used via
// testImplementation / screenshotTestImplementation / a debug-only demo binding in :app.
// May depend on: :core:data (contracts), :core:model, :core:common, :core:session.
plugins {
    id("flocket.android.library")
}

dependencies {
    api(projects.core.data)
    api(projects.core.model)
    api(projects.core.common)
    api(projects.core.session)
    api(libs.kotlinx.coroutines.test)
    api(libs.junit)
    api(libs.turbine)
}
