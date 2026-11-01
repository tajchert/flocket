// :core:protocol - Transport-agnostic herdr-core wire protocol: ByteTransport port, NDJSON/framed codec,
// JSON-RPC client and DTOs. May depend on: :core:model, :core:common, kotlinx-serialization/coroutines.
// No Android, no SSH. The java-test-fixtures source set ships PipeTransport, ProcessTransport and FakeCore
// for the session layer's tests.
plugins {
    id("flocket.jvm.library")
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testFixturesApi(projects.core.model)
    testFixturesApi(projects.core.common)
    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesApi(libs.kotlinx.serialization.json)
    testFixturesApi(libs.kotlinx.coroutines.test)
    testFixturesApi(libs.junit)
}

tasks.withType<Test>().configureEach {
    systemProperty("flocket.fixtures.dir", rootProject.projectDir.resolve("../protocol/fixtures").canonicalPath)
    testLogging { events("passed", "skipped", "failed") }
}
