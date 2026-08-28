// Convention plugin for `base:kernel` — the multiplatform module of code shared between the JVM backend
// (`base:common` and friends) and the Kotlin/JS frontend (`webapp`). Unlike `kdr.kotlin-conventions` (plain
// JVM) and `kdr.kotlin-multiplatform-conventions` (bare, used by `webapp` which declares its own JS target),
// this sets up BOTH a `jvm()` and a `js()` target so the same `commonMain` code compiles to backend and
// frontend, and so its tests can run on both.
//
// Test strategy: JVM tests use the repo's usual Kotest stack (jvmTest); the JS target runs a small
// `commonTest` proof suite written in the multiplatform `kotlin.test`, so the exact same source executes on
// both targets. Kotest's JUnit5 runner is JVM-only, hence the split.
import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("multiplatform")
    // Dokka: resolves KDoc `[link]`s so the build can fail on dangling ones (issue #491).
    id("org.jetbrains.dokka")
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)

    jvm()
    js {
        nodejs()
    }

    sourceSets {
        commonMain {
            dependencies {
                // KMP-friendly date/time (the Instant type itself comes from the kotlin.time stdlib). `api`
                // because DateUtil's surface exposes kotlinx-datetime types to downstream modules.
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
            }
        }
        commonTest {
            dependencies {
                // Multiplatform test framework: commonTest sources run on BOTH the jvm and js targets.
                implementation(kotlin("test"))
            }
        }
        jvmTest {
            dependencies {
                // JVM-only Kotest, matching the rest of the repo's tests.
                implementation("io.kotest:kotest-runner-junit5:6.2.1")
                implementation("io.kotest:kotest-assertions-core:6.2.1")
            }
        }
    }
}

// Kotest (the JVM test suites) runs on the JUnit Platform.
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
