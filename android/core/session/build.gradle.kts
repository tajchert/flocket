// :core:session - CoreSession: connection epochs, hello policy, event reducer, reconnect/backoff,
// attach sessions and ActionOutcome. May depend on: :core:protocol, :core:model, :core:common. No Android.
plugins {
    id("flocket.jvm.library")
}

dependencies {
    api(projects.core.protocol)
    api(projects.core.model)
    api(projects.core.common)
    api(libs.kotlinx.coroutines.core)
}

dependencies {
    testImplementation(testFixtures(projects.core.protocol))
}

tasks.withType<Test>().configureEach {
    systemProperty("flocket.fixtures.dir", rootProject.projectDir.resolve("../protocol/fixtures").canonicalPath)
    testLogging { events("skipped", "failed") }
}
