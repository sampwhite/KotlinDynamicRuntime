// `base/common` — the foundational module. Standard source tree
// (src/main/kotlin, src/test/kotlin, src/main/resources). No project
// dependencies; everything else depends, directly or transitively, on this.
// The Kotlin toolchain, repositories, and the Kotest test stack all come from
// the `kdr.kotlin-conventions` plugin.
plugins {
    id("kdr.kotlin-conventions")
}

dependencies {
    api("org.jetbrains.kotlin:kotlin-reflect")

    // Logging backend. `implementation`, not `api`: KdrLogger hides log4j2 behind
    // our own LogLevel/topic surface, so downstream modules never see log4j types
    // on their compile classpath. log4j-core supplies both the runtime and the
    // programmatic configuration API used by LogSetup.
    implementation("org.apache.logging.log4j:log4j-api:2.26.1")
    implementation("org.apache.logging.log4j:log4j-core:2.26.1")
}
