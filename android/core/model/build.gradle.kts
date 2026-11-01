// :core:model - Pure domain types shared by every module (CoreEndpoint, TerminalRef, TerminalRecord,
// AgentStatus, ...). May depend on: nothing (kotlinx-serialization annotations at most). No Android.
plugins {
    id("flocket.jvm.library")
}
