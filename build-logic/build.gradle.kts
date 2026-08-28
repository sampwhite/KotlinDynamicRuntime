plugins {
    // Lets us write convention plugins as precompiled `*.gradle.kts` scripts
    // under src/main/kotlin. Each such file becomes a real Gradle plugin whose
    // id is the file name (minus the `.gradle.kts` suffix).
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Putting the Kotlin Gradle plugin on the build-logic classpath does two
    // things: it lets the convention plugin call `plugins { kotlin("jvm") }`
    // and configure the typed `kotlin { }` extension, AND it pins the Kotlin
    // version for every module that applies the convention. This versioned file
    // is now the single home for that version.
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    // Dokka generates API docs, but we apply it for a side effect that matters more here: it resolves every
    // KDoc `[link]` and warns on any that dangles. With `failOnWarning` (set in the convention plugins) that
    // turns a renamed/moved/misspelled doc link into a build failure instead of something caught, if ever, in
    // a later manual review. See issue #491.
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:2.2.0")
}
