// :core:database - Room database for hosts and pinned host keys. May depend on: :core:model.
plugins {
    id("flocket.android.library")
    id("flocket.hilt")
    id("flocket.android.room")
}

dependencies {
    implementation(projects.core.model)
    implementation(libs.kotlinx.coroutines.android)
}
