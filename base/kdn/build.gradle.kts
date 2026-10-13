// `base/kdn` — the dynamic-runtime core. Standard source tree. Depends on
// `common`.
plugins {
    id("kdr.kotlin-conventions")
}

dependencies {
    implementation(project(":base:common"))
}

// The demo client's documentation suite (issue #1136), copied from the repo's `docs/demo-client/` onto the classpath
// as Markdown *documents*, so MarkdownDocService serves them in-app -- the arrangement `base/common`'s `embedDocs`
// uses for the README. A document id cannot hold a `/`, so each file is flattened to `demo-client-<name>.md`: its
// doc id is `demo-client-<name>`, and its source path, which a link resolves by, stays `docs/demo-client/<name>.md`.
// Keep the ids in step with the documents `KdnComponent` declares.
val embedDocs by tasks.registering(Copy::class) {
    // Resolved from this module's own directory (base/kdn -> the repo root), so it does not depend on what the
    // checkout directory is named.
    from(layout.projectDirectory.dir("../../docs/demo-client")) {
        include("*.md")
        rename { "demo-client-$it" }
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
