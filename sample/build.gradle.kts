// `sample` — a self-contained example module, isolated from the main launcher.
// It runs a small Ktor HTTP server exposing an in-memory Todo REST API that the
// `webapp` front end consumes. Nothing in the main build depends on it; it is a
// standalone demo you start with `./gradlew :sample:run`.
plugins {
    id("kdr.kotlin-conventions")
    // kotlinx.serialization compiler plugin — enables `@Serializable` on the DTOs
    // so Ktor's JSON content negotiation can (de)serialize them. Version matches
    // the Kotlin version pinned in build-logic (2.4.0).
    kotlin("plugin.serialization") version "2.4.0"
    application
}

// Ktor 3.x. Kept as a local val rather than a version catalog to match this
// module's self-contained, example nature.
val ktorVersion = "3.2.0"

dependencies {
    // Ktor server: the engine (Netty), core routing, JSON content negotiation
    // via kotlinx.serialization, and CORS so the webapp (served from :8080 in
    // dev) can call this API on :8081.
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")

    // Ktor logs through SLF4J; Logback is the runtime backend.
    implementation("ch.qos.logback:logback-classic:1.5.18")

    // Integration testing: `testApplication` boots the app in-process (no real
    // socket), and the client-side content negotiation lets the test client
    // send/receive the typed DTOs over the in-memory transport.
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
}

application {
    mainClass.set("com.dynamicruntime.sample.todo.TodoServerKt")
}
