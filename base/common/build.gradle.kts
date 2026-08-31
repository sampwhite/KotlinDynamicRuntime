// `base/common` — the foundational module. Standard source tree
// (src/main/kotlin, src/test/kotlin, src/main/resources). No project
// dependencies; everything else depends, directly or transitively, on this.
// The Kotlin toolchain, repositories, and the Kotest test stack all come from
// the `kdr.kotlin-conventions` plugin.
plugins {
    id("kdr.kotlin-conventions")
}

dependencies {
    // The shared multiplatform kernel (issue #56): the universal exception, the JSON/date/string/collection
    // utilities, the template evaluator, and the JSON-Schema model/parser/validator. `api` because these
    // types appear throughout `common`'s own public surface, so downstream modules must see them too. Package
    // names were preserved on the move, so call sites are unchanged.
    api(project(":base:kernel"))

    api("org.jetbrains.kotlin:kotlin-reflect")

    // Logging backend. `implementation`, not `api`: KdrLogger hides log4j2 behind
    // our own LogLevel/topic surface, so downstream modules never see log4j types
    // on their compile classpath. log4j-core supplies both the runtime and the
    // programmatic configuration API used by LogSetup.
    implementation("org.apache.logging.log4j:log4j-api")
    implementation("org.apache.logging.log4j:log4j-core")

    // Jetty 12-core HTTP server. We use the core Handler API handleRequest,
    // Response, Callback directly -- no servlet layer -- so this is the only
    // Jetty artifact we need (it brings jetty-http/io/util transitively).
    // Version comes from the jetty-bom applied in `kdr.kotlin-conventions`.
    implementation("org.eclipse.jetty:jetty-server")
    // Jetty's HTTP client. Declared here rather than in `edge`, which is where the first use is (the reverse
    // proxy's data plane, issue #419), because it is intended to become the one outbound client for the whole
    // codebase behind a convenience layer of ours -- replacing the direct `java.net.http` use in MailService
    // and GoogleJwksKeySource (issue #420). It belongs to common on those merits, whether or not an edge
    // exists, and putting it here now means #420 does not have to move it.
    implementation("org.eclipse.jetty:jetty-client")
    // Jetty logs through slf4j; this binding routes that into our log4j2 config
    // (and brings slf4j-api transitively).
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl")

    // Database drivers, loaded via the JDBC DriverManager (ServiceLoader) at runtime. H2 backs the
    // in-memory and file-based modes (and the tests); PostgreSQL backs real deployments.
    implementation("com.h2database:h2:2.4.240")
    implementation("org.postgresql:postgresql:42.7.13")
}

// Publish the repository's README as a Markdown *document* resource, so MarkdownDocService can serve it (and
// the home page can link to it) from the classpath -- identically whether launched via Gradle or from a built
// jar. The README lives at the repo root, outside any source set, so it is copied into a generated resources
// directory laid out as `md-docs/readme.md` rather than duplicated in the tree (the same arrangement `appui`
// uses to embed the webapp bundle). Because the served build id is a content hash, re-copying an unchanged
// README keeps its URL stable.
val embedDocs by tasks.registering(Copy::class) {
    // Resolved from this module's own directory (base/common -> the repo root), so it does not depend on what
    // the checkout directory is named.
    from(layout.projectDirectory.file("../../README.md")) {
        rename { "readme.md" }
    }
    // The repo docs the README links to (issue #492): served in-app so those interior links resolve to a
    // document rather than the source repository. Their file names are their doc ids (code-guide.md ->
    // docId "code-guide"); keep this list in step with the HDOC registry in HomeEndpoints.
    from(layout.projectDirectory.dir("../..")) {
        include(
            "code-guide.md", "client-definition.md", "deferred-work.md",
            "gedra-config-and-data.md", "gedra-entry.md", "gedra-patch.md", "ui-block.md",
        )
    }
    into(layout.buildDirectory.dir("docResources/md-docs"))
}

sourceSets {
    main {
        // The generated docs join this module's resources, so they land on the runtime classpath.
        resources.srcDir(layout.buildDirectory.dir("docResources"))
    }
}

tasks.named("processResources") {
    dependsOn(embedDocs)
}
