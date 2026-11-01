// :core:data - Repositories and ConnectionManager (per-host sessions, lifecycle, network).
// May depend on: :core:session, :core:ssh, :core:protocol, :core:model, :core:common, :core:database,
// :core:datastore, :core:security.
plugins {
    id("flocket.android.library")
    id("flocket.hilt")
}

dependencies {
    // Public contracts expose model, common and session types to features.
    api(projects.core.model)
    api(projects.core.common)
    api(projects.core.session)
    implementation(projects.core.protocol)
    implementation(projects.core.ssh)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.security)
    implementation(libs.kotlinx.coroutines.android)
}
