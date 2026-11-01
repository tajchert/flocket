// :feature:/Users/mtajchert/coding/priv/flocket/.worktrees/features/android/terminalspi - Navigation keys and public contracts of the terminals feature. Other modules may depend on
// this module. May depend on: :core:model, Navigation 3 runtime. Never on an :impl module.
plugins {
    id("flocket.android.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.androidx.navigation3.runtime)
    implementation(libs.kotlinx.serialization.json)
}
