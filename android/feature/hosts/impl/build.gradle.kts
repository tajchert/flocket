// :feature:hosts:impl - Screens, ViewModels and navigation entries of the hosts feature. Only :app depends on
// it. May depend on: its own :api, other features' :api, :core:* (the feature plugin adds the defaults).
plugins {
    id("flocket.android.feature")
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.session)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    testImplementation(projects.core.testing)
    screenshotTestImplementation(projects.core.testing)
    screenshotTestImplementation(projects.core.designsystem)
    screenshotTestImplementation(projects.core.ui)
    implementation(projects.feature.onboarding.api)
}

// The helper and the agent instruction live once, in protocol/tools/. They are copied (not committed) into a
// generated assets folder, so the APK ships them byte for byte.
val syncFlocketShareAssets =
    tasks.register<Sync>("syncFlocketShareAssets") {
        from(rootProject.file("../protocol/tools")) { include("flocket-share", "agent-instruction.md") }
        into(layout.buildDirectory.dir("generated/flocketShareAssets"))
    }

android {
    sourceSets.getByName("main").assets.srcDir(
        layout.buildDirectory
            .dir("generated/flocketShareAssets")
            .get()
            .asFile,
    )
}

// Every task that reads assets (merge, lint, packaging) must see the copied files first.
tasks
    .matching {
        it.name != "syncFlocketShareAssets" &&
            it.name.matches(Regex(".*(Assets|AssetsAndroidTest|Lint.*|lint.*)"))
    }.configureEach { dependsOn(syncFlocketShareAssets) }
