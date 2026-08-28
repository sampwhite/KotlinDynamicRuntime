plugins {
    kotlin("multiplatform")
    // Dokka: resolves KDoc `[link]`s so the build can fail on dangling ones (issue #491).
    id("org.jetbrains.dokka")
}

repositories {
    mavenCentral()
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
