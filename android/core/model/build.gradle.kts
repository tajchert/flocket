// :core:model - Pure domain types shared by every module (Host, CoreEndpoint, TerminalRef,
// TerminalRecord, AgentStatus, ConnectionState, ...). May depend on: :core:common. No Android.
plugins {
    id("flocket.jvm.library")
}

dependencies {
    api(projects.core.common)
}
