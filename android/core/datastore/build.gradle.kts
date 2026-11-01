// :core:datastore - Preferences DataStore for user settings. May depend on: :core:model.
plugins {
    id("flocket.android.library")
    id("flocket.hilt")
}

dependencies {
    implementation(projects.core.model)
    api(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
}
