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

    // The AWS SDK's S3 client, behind the file store's `s3` provider (issue #1129) -- the code guide's exception:
    // a library to talk to a third party. `implementation`, and confined to one file (`SdkS3Api`), so no SDK
    // type reaches another module. The SDK's heavier HTTP clients are excluded and its JDK-backed one named
    // instead: one small client on the classpath rather than Apache HttpClient or Netty beside the Jetty
    // client the rest of the codebase calls out with. At 2.55 only the Apache 5 client arrives transitively;
    // the other two are named so an SDK upgrade that reshuffles its defaults does not bring one in unnoticed.
    // The SDK logs through slf4j, which the binding above already routes into our log4j2 config. Versions
    // come from the AWS BOM applied in `kdr.kotlin-conventions`.
    implementation("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
    }
    implementation("software.amazon.awssdk:url-connection-client")
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
    // document rather than the source repository. Each is copied under its doc id, which is its name in the
    // classpath (docs/development/code-guide.md -> md-docs/code-guide.md, docId "code-guide"). The ids outlived
    // the files' move under docs/ (issue #1294), so in-app links to them still work. Keep this map in step with
    // the HDOC registry and coreDocs() in HomeEndpoints, which carries the same paths.
    val inAppDocs = mapOf(
        "docs/extending.md" to "extending",
        "docs/api-keys-and-tokens.md" to "api-keys-and-tokens",
        "docs/api-guide.md" to "api-guide",
        "docs/development/code-guide.md" to "code-guide",
        "docs/development/deferred-work.md" to "deferred-work",
        "docs/design/client-definition.md" to "client-definition",
        "docs/design/gedra/config-and-data.md" to "gedra-config-and-data",
        "docs/design/gedra/entry.md" to "gedra-entry",
        "docs/design/gedra/states.md" to "gedra-states",
        "docs/design/ui-block.md" to "ui-block",
        "docs/design/reporting.md" to "reporting",
        "docs/design/file-refs.md" to "file-refs",
        "docs/design/sandboxes.md" to "sandboxes",
        "docs/design/user-search.md" to "user-search",
        "docs/design/api-keys.md" to "api-keys",
        "docs/history/gedra-workflow.md" to "gedra-workflow",
    )
    for ((path, docId) in inAppDocs) {
        from(layout.projectDirectory.file("../../$path")) {
            rename { "$docId.md" }
        }
    }
    // The MIT license the README links to (issue #555): extension-less at the repo root, so it is copied in
    // and given the `.md` name MarkdownDocService serves under (docId "license"). The plain-text license
    // renders fine as Markdown, and the README's `[MIT](LICENSE)` link then resolves to an in-app document.
    from(layout.projectDirectory.file("../../LICENSE")) {
        // Derive the served name from the source rather than a constant, so adding another extension-less
        // root file here (NOTICE, COPYING) does not silently rename both to one name and clobber a document.
        rename { "${it.lowercase()}.md" }
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

// RepoDocLinksTest (issues #1294, #1474) reads the repository's Markdown straight from the tree, not from the
// classpath, so Gradle would know nothing of it: a change to Markdown alone would leave the tests up to date, and a
// broken link would pass the build. So the documents are listed once, here, and handed to the test as a system
// property by an argument provider whose properties are the task's inputs: what the test reads and what reruns it
// cannot drift apart. The other files of the documentation trees are inputs too, since documents link to them (an
// image, an example file). A link to source code needs nothing more, since the sources are on the classpath.
//
// The check runs in a task of its own, so a change to Markdown reruns it alone rather than the module's whole suite;
// `test` leaves it out, and `check` runs both. Run it alone with `./gradlew :base:common:docLinksTest`.
class RepoDocsArgument(
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) val docs: FileCollection,
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) val linkTargets: FileCollection,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Dkdr.repoDocs=" + docs.files.sorted().joinToString(File.pathSeparator))
}

val docLinksTestClass = "com.dynamicruntime.common.content.RepoDocLinksTest"
val docTrees = listOf("docs", "examples", ".claude/skills")
// Resolved from this module's own directory (base/common -> the repo root), as `embedDocs` is.
val repoRoot = layout.projectDirectory.dir("../..")

val docLinksTest by tasks.registering(Test::class) {
    description = "Checks that every link in the repository's documentation resolves."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching(docLinksTestClass)
    jvmArgumentProviders.add(
        RepoDocsArgument(
            // Every Markdown file at the repository's root, `webapp/CLAUDE.md`, and the Markdown of the trees.
            docs = files(
                repoRoot.asFile.listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty(),
                repoRoot.file("webapp/CLAUDE.md"),
                docTrees.map { tree -> fileTree(repoRoot.dir(tree)) { include("**/*.md") } },
            ),
            linkTargets = files(docTrees.map { tree -> fileTree(repoRoot.dir(tree)) { exclude("**/*.md") } }),
        ),
    )
}

tasks.named<Test>("test") {
    filter.excludeTestsMatching(docLinksTestClass)
}

tasks.named("check") {
    dependsOn(docLinksTest)
}
