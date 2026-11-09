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
// The walk-through that starts with the demo (issue #1429) is copied beside them.
// Keep the ids in step with the documents `KdnComponent` declares.
val embedDocs = tasks.register<Copy>("embedDocs") {
    // Resolved from this module's own directory (base/kdn -> the repo root), so it does not depend on what the
    // checkout directory is named.
    from(layout.projectDirectory.dir("../../docs/demo-client")) {
        include("*.md")
        rename { "demo-client-$it" }
    }
    // The walk-through a reference deployment links from a signed-in visitor's home page (issue #1429): its doc id is
    // its file name, `walk-through`.
    from(layout.projectDirectory.file("../../docs/walk-through.md"))
    // dynamicruntime.org's privacy policy and terms (issue #1711), flattened as the demo's pages are:
    // `dynamicruntime-org-privacy-policy`, `dynamicruntime-org-terms-of-service`.
    from(layout.projectDirectory.dir("../../docs/dynamicruntime-org")) {
        include("*.md")
        rename { "dynamicruntime-org-$it" }
    }
    into(layout.buildDirectory.dir("docResources/md-docs"))
}

// The walk-through's banners (issues #1429, #1463), copied from the repo's `docs/images/` beside the documents, where the
// page names them by their repo path so GitHub draws them too. `WalkThrough.images` declares them with that path.
val embedImages = tasks.register<Copy>("embedImages") {
    from(layout.projectDirectory.dir("../../docs/images")) {
        include("walk-part*.svg")
    }
    into(layout.buildDirectory.dir("docResources/component-images"))
}

sourceSets {
    main {
        // The generated docs and images join this module's resources, so they land on the runtime classpath.
        resources.srcDir(layout.buildDirectory.dir("docResources"))
    }
}

tasks.named("processResources") {
    dependsOn(embedDocs, embedImages)
}

// The module's specs shared among three JVMs (issue #1581): its test task is the last step of the gate's critical path,
// and one JVM took about 25 seconds, three about 12 (two 15, four 11). Each JVM still runs its specs one at a time, in
// the order of their qualified names, and boots its own shared instances. Which specs ran before a given one in its JVM
// is recorded in `build/spec-order/test` (see `kdr.kotlin-conventions`), for a failure that depends on it, and
// `-Pkdr.testForks=1` puts the specs named with `--tests` back in one JVM, in that order, to reproduce it.
tasks.named<Test>("test") {
    val forks = providers.gradleProperty("kdr.testForks").orNull
    maxParallelForks = forks?.let {
        requireNotNull(it.toIntOrNull()?.takeIf { n -> n >= 1 }) {
            "-Pkdr.testForks is '$it'; it has to be a whole number of JVMs, 1 or more."
        }
    } ?: 3
}
