// `base/kernel` — the multiplatform module of code shared between the JVM backend and the Kotlin/JS
// frontend. Its `commonMain` holds pure, transpile-safe Kotlin (no `java.*`, no reflection): the universal
// exception, JSON/date/string/collection utilities, the template evaluator, and the JSON-Schema model with
// its parser and validator. `base:common` depends on this module's JVM variant and `webapp` on its JS
// variant, so both run the exact same logic. Targets, toolchain, and the test stack come from the
// `kdr.kotlin-kernel-conventions` convention plugin.
//
// Package note (issue #56): moved code keeps its original `com.dynamicruntime.common.*` packages rather than
// moving into a `kernel` package, so no call site changes when a file relocates here.
plugins {
    id("kdr.kotlin-kernel-conventions")
}
