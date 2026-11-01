// :core:protocol - Transport-agnostic herdr-core wire protocol: ByteTransport port, NDJSON/framed codec,
// JSON-RPC client and DTOs. May depend on: :core:model, :core:common, kotlinx-serialization/coroutines.
// No Android, no SSH.
plugins {
    id("flocket.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
