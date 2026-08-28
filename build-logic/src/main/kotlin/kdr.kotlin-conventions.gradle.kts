// Convention plugin shared by every Kotlin module in the KotlinDynamicRuntime
// build. Modules apply it with `plugins { id("kdr.kotlin-conventions") }`.
//
// Because this is a precompiled script plugin (compiled by the `build-logic`
// build), it gets full type-safe accessors -- `kotlin { }` resolves here just
// like in a normal build script -- which is why this is preferred over the
// older `apply(from = ...)` script approach.

import com.dynamicruntime.buildlogic.Versions
import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("jvm")
    // Dokka: resolves KDoc `[link]`s so the build can fail on dangling ones (issue #491).
    id("org.jetbrains.dokka")
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    // Dependency-family BOMs (see `Versions`). These add nothing to any classpath -- a platform only
    // *constrains* versions -- so every module can carry them harmlessly, and the module that uses one of
    // these families names its artifacts without a version. Declared here rather than per-module so a new
    // module cannot acquire a jetty or log4j artifact at some other version by forgetting to opt in.
    add("implementation", platform("org.eclipse.jetty:jetty-bom:${Versions.jetty}"))
    add("implementation", platform("org.apache.logging.log4j:log4j-bom:${Versions.log4j}"))

    // KMP-friendly date/time (the Instant type itself comes from the kotlin.time stdlib).
    add("implementation", "org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")

    // Every module gets the Kotest stack on its test classpath.
    add("testImplementation", "io.kotest:kotest-runner-junit5:6.2.1")
    add("testImplementation", "io.kotest:kotest-assertions-core:6.2.1")
}

// Kotest runs on the JUnit Platform.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}


// Resolve every KDoc `[link]` and fail the build on any that dangles (issue #491). Dokka is the only tool in
// this build that resolves doc links; without this gate a renamed `$ref`, a comment moved to a package that
// doesn't import its target, or a `[localVar]` that was never a declaration all rot silently until a manual
// review happens to notice. NOTE: this catches dangling *references* only, not *orphaned* doc comments (a
// `/**` block stranded above the wrong declaration is validly attached, so Dokka sees nothing wrong).
dokka {
    dokkaPublications.configureEach {
        failOnWarning.set(true)
    }
}

// Fold the link check into `./gradlew check`, the repo's one gate, so it runs on every build rather than only
// when someone thinks to generate docs. `dokkaGenerate` also writes HTML into `build/dokka/` -- an unused
// side effect of the only task that performs the validation.
tasks.named("check") {
    dependsOn("dokkaGenerate")
}
