// :core:database - Room database for hosts and pinned host keys. May depend on: :core:model.
plugins {
    id("flocket.android.library")
    id("flocket.hilt")
    id("flocket.android.room")
}

dependencies {
    implementation(projects.core.model)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.room.testing)
}

// MigrationTestHelper (Robolectric) reads the exported schemas from the test assets.
android {
    sourceSets
        .getByName("test")
        .assets.directories
        .add("$projectDir/schemas")
}
