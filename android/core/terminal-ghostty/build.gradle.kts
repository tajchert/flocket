// :core:terminal-ghostty - TerminalEngine implementation over libghostty-vt (JNI shim + prebuilt .so).
// May depend on: :core:terminal, :core:common.
plugins {
    id("flocket.android.library")
}

dependencies {
    implementation(projects.core.terminal)
    implementation(projects.core.common)
}
