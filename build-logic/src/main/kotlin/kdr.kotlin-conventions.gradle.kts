// Convention plugin shared by every Kotlin module in the KotlinDynamicRuntime
// build. Modules apply it with `plugins { id("kdr.kotlin-conventions") }`.
//
// Because this is a precompiled script plugin (compiled by the `build-logic`
// build), it gets full type-safe accessors -- `kotlin { }` resolves here just
// like in a normal build script -- which is why this is preferred over the
// older `apply(from = ...)` script approach.

import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    // Every module gets the Kotest stack on its test classpath.
    "testImplementation"("io.kotest:kotest-runner-junit5:6.2.1")
    "testImplementation"("io.kotest:kotest-assertions-core:6.2.1")
}

// Kotest runs on the JUnit Platform.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
