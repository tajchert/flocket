// :core:security - Android Keystore wrapping, device key generation/import and secret storage.
// May depend on: :core:model, :core:common, :core:ssh (key generation/parsing only).
plugins {
    id("flocket.android.library")
    id("flocket.hilt")
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.ssh)
    implementation(libs.kotlinx.coroutines.core)
}
