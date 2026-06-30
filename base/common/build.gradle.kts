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
}
