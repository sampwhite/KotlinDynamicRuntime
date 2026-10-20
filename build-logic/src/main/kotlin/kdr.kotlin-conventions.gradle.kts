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
    add("implementation", platform("software.amazon.awssdk:bom:${Versions.awsSdk}"))

    // KMP-friendly date/time (the Instant type itself comes from the kotlin.time stdlib).
    add("implementation", "org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")

    // Every module gets the Kotest stack on its test classpath.
    add("testImplementation", "io.kotest:kotest-runner-junit5:6.2.1")
    add("testImplementation", "io.kotest:kotest-assertions-core:6.2.1")
}

/** The Gradle property, and the test JVM's system property, that turns on concurrent specs (issue #1078). */
val concurrentSpecsProperty = "kdr.concurrentSpecs"

// Kotest runs on the JUnit Platform.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Gradle's default test-worker heap is 512m, and a suite here boots many in-memory instances into one
    // worker JVM -- each retaining its own schema store -- so the default sat right at the edge: adding a
    // couple of state traits (issue #794) was enough to tip `:base:kdn:test` into OutOfMemoryError. Raised
    // here, rather than per module, so every suite gets the same headroom. 1g was outgrown in turn by #990's
    // extension tests: `InstanceRegistry` keeps every instance a suite boots for the life of the JVM, so the
    // suite's heap only grows, and raising the ceiling postpones rather than ends this.
    maxHeapSize = "1536m"
    // Concurrent specs, for an occasional bug-finding run (issue #1078): `-Pkdr.concurrentSpecs=4` runs four specs
    // at a time, each on its own thread, in the modules carrying a Kotest `ProjectConfig` that reads it. Unset, specs
    // run one after another, as always. Declared as the test's system property, so changing it reruns the tests.
    // Checked here rather than in the config, which Kotest loads quietly: a mistyped value there would run the specs
    // one at a time without saying so, when the run was asked for to find concurrency bugs.
    providers.gradleProperty(concurrentSpecsProperty).orNull?.let { value ->
        require((value.toIntOrNull() ?: 0) >= 1) {
            "-P$concurrentSpecsProperty is '$value'; it has to be a whole number of specs at a time, 1 or more."
        }
        systemProperty(concurrentSpecsProperty, value)
    }
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
