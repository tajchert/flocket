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

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.bundles.lifecycle)
    implementation(libs.bundles.navigation3)
    implementation(libs.bundles.adaptive)
    implementation(libs.kotlinx.coroutines.android)
}
