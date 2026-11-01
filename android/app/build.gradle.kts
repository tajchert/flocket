// :app - Application shell: single Activity, Navigation 3 host, Hilt graph. The only module that may depend
// on every :feature:*:impl.
plugins {
    id("flocket.android.application")
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.mtajchert.flocket"
    defaultConfig {
        applicationId = "com.mtajchert.flocket"
    }
}

baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
    mergeIntoMain = true
}

dependencies {
    baselineProfile(projects.benchmark)
    implementation(libs.androidx.profileinstaller)

    implementation(projects.core.designsystem)
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.data)
    // Ships the libghostty-vt + JNI shim .so files (both ABIs) in the APK.
    implementation(projects.core.terminalGhostty)
    implementation(projects.core.ssh)
    implementation(projects.core.security)
    implementation(projects.core.ui)

    // Each feature contributes its entries through a Hilt multibinding (EntryProviderInstaller); :app is the
    // only module that sees both halves. Keys come from :api, screens from :impl.
    implementation(projects.feature.onboarding.api)
    implementation(projects.feature.onboarding.impl)
    implementation(projects.feature.hosts.api)
    implementation(projects.feature.hosts.impl)
    implementation(projects.feature.terminals.api)
    implementation(projects.feature.terminals.impl)
    implementation(projects.feature.terminal.api)
    implementation(projects.feature.terminal.impl)
    implementation(projects.feature.settings.api)
    implementation(projects.feature.settings.impl)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.bundles.lifecycle)
    implementation(libs.bundles.navigation3)
    implementation(libs.bundles.adaptive)
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite")
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
}
