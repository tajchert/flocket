// :core:ssh - SSHJ-backed ByteTransport (exec channel, no PTY), host-key verification port and hc
// discovery. May depend on: :core:protocol, :core:model, :core:common, SSHJ, BouncyCastle. No Android.
plugins {
    id("flocket.jvm.library")
}

dependencies {
    api(projects.core.protocol)
    api(projects.core.model)
    api(projects.core.common)
    // SSHJ and BouncyCastle are wired in by the SSH implementation task:
    // implementation(libs.sshj); implementation(libs.bouncycastle.prov); implementation(libs.bouncycastle.pkix)
    // testImplementation(libs.mina.sshd.core)
}
