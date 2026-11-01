// :core:terminal - TerminalEngine interface, FakeTerminalEngine and the Compose cell-grid renderer with
// IME/hardware-keyboard input. May depend on: :core:designsystem, :core:model, :core:common.
plugins {
    id("flocket.android.library")
    id("flocket.android.compose")
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.model)
    implementation(projects.core.common)
}
