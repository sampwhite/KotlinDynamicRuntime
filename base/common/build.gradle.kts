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

    // Jetty 12-core HTTP server. We use the core Handler API handleRequest,
    // Response, Callback directly -- no servlet layer -- so this is the only
    // Jetty artifact we need (it brings jetty-http/io/util transitively).
    implementation("org.eclipse.jetty:jetty-server:12.1.10")
    // Jetty logs through slf4j; this binding routes that into our log4j2 config
    // (and brings slf4j-api transitively).
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.26.1")

    // Database drivers, loaded via the JDBC DriverManager (ServiceLoader) at runtime. H2 backs the
    // in-memory and file-based modes (and the tests); PostgreSQL backs real deployments.
    implementation("com.h2database:h2:2.4.240")
    implementation("org.postgresql:postgresql:42.7.13")
}
