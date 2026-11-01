// :core:data - Repositories and ConnectionManager (per-host sessions, lifecycle, network).
// May depend on: :core:session, :core:ssh, :core:protocol, :core:model, :core:common, :core:database,
// :core:datastore, :core:security.
plugins {
    id("flocket.android.library")
    id("flocket.hilt")
}

dependencies {
    // Public contracts expose model, common, session and transport types to features and :app.
    api(projects.core.model)
    api(projects.core.common)
    api(projects.core.session)
    api(projects.core.protocol)
    implementation(projects.core.ssh)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.security)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.process)

    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
