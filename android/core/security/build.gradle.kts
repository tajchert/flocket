// :core:security - Android Keystore wrapping, device key generation/import and secret storage.
// May depend on: :core:model, :core:common.
plugins {
    id("flocket.android.library")
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
}
