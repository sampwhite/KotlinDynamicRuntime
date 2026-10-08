// `appui` — the JVM host that serves the self-contained webapp. It contributes no endpoints; its
// AppUiService is a ContentServer (registered with the runtime's request dispatcher) that serves the browser
// bundle under the `wa` context root. The Kotlin/JS front end itself lives in `:webapp`; this module packages
// that module's *production* bundle as a jar of its own (`appui-webapp.jar`, see `webappJar` below), which
// `:launch` puts on the running server's classpath, so it can serve it with no separate webpack dev server and no
// API proxy (calls are same-origin with the runtime).
plugins {
    id("kdr.kotlin-conventions")
}

dependencies {
    // `config` re-exports the base modules (common + kdn) via `api`: the component/service model, the
    // InstanceRegistry, the ContentServer hook, and the HTTP request types. The same dependency `launch` uses.
    implementation(project(":config"))
}

// Embed the webapp's production bundle as a classpath resource. The copy pulls the assets produced by
// `:webapp:jsBrowserDistribution` (webapp.js + its sourcemap, plus the stylesheet, favicon and brand mark the
// webapp authors) into a generated resources directory laid out under `webapp/`, so AppUiService can read
// them at `/webapp/webapp.js` from the classpath — identically whether launched via `:launch:run` or from a
// built jar. Referencing the webapp's build dir lazily (a DirectoryProperty provider) plus a task-path
// `dependsOn` avoids eagerly evaluating the sibling project.
//
// Embedding `app.css` (rather than the production shell keeping its own copy of the CSS) is what keeps the
// two shells from drifting: the webapp authors one stylesheet and both shells link it. See the note at the
// top of `webapp/src/jsMain/resources/app.css`.
//
// `index.html` is deliberately NOT copied: the production shell is rendered by AppUiPage (it has to inject the
// live context roots), so only the assets that shell references are embedded.
// Which build of the webapp to embed (issue #230). `-Pwebapp.dev=true` embeds the **readable** development
// bundle in place of the production one: same filename, same resource directory, so nothing downstream changes
// -- the shell links it, the hash is computed from it, and the app behaves identically. What differs is that a
// crash reports `NullPointerException at ensureNotNull at SchemaForm$lambda` instead of `ji` at a byte offset.
//
// One or the other, never both, and that is a constraint rather than a preference. Kotlin/JS runs both
// executable modes through a single compile-sync directory
// (`build/js/packages/<project>/kotlin`), and the two write *different content to the same path* -- verified.
// Depending on both distributions in one invocation therefore fails Gradle's validation, and forcing an order
// would leave a build that is correct only by accident of scheduling. Separate invocations are fine because
// each syncs and then builds a single mode.
//
// Costs of the readable build: ~24 MB rather than ~2 MB, and a slower first load. It is a troubleshooting
// build, not a deployable one.
val useDevWebapp = (project.findProperty("webapp.dev") as? String) == "true"
val webappDistTask = if (useDevWebapp) {
    ":webapp:jsBrowserDevelopmentExecutableDistribution"
} else {
    ":webapp:jsBrowserDistribution"
}
val webappDistDir = if (useDevWebapp) "dist/js/developmentExecutable" else "dist/js/productionExecutable"

// The files the webapp authors and the distribution carries through verbatim: the stylesheet and the artwork. The
// rasters are binary, so AppUiService serves them as bytes; a copy moves them unchanged (verified: md5 matches the
// branding source through the webpack distribution). Named once, for the embedded bundle and the tests' (below).
val webappAuthoredAssets = listOf(
    "app.css", "favicon.svg", "brand-mark.svg", "favicon.ico", "favicon-32.png", "apple-touch-icon.png",
)

val embedWebapp = tasks.register<Sync>("embedWebapp") {
    description = "Embed the web application" + if (useDevWebapp) " (readable development build)" else ""
    dependsOn(webappDistTask)
    from(project(":webapp").layout.buildDirectory.dir(webappDistDir)) {
        // `webapp.js.map` matches nothing in the development build -- that one carries its source map
        // INLINE as a data URI, which is most of why it is twelve times the size. A `Sync` rather than a
        // `Copy` for exactly that reason: switching builds must *remove* the previous one's leftovers, or
        // a stale production sourcemap would sit beside a development bundle claiming to describe it.
        include(listOf("webapp.js", "webapp.js.map") + webappAuthoredAssets)
    }
    into(layout.buildDirectory.dir("webappResources/webapp"))
}

// The bundle ships as a jar of its own (issue #1076), published on a configuration of its own --
// `webappBundleElements` -- that `:launch` asks for where the app runs or ships: `run` (and an IDE run through
// Gradle), the start scripts and distribution, and the pathing jar behind `bin/kdr-run`. There it is laid out under
// `webapp/` on the classpath exactly as before. It is deliberately **not** on `runtimeElements`, nor in this
// module's main resources: then every test classpath reaching this module (its own, `:launch`'s, `:multiNodeTest`'s)
// and even compiling this module's tests (the Kotlin plugin makes the main jar their friend module) would need the
// bundle, and building it is a production Kotlin/JS compile and webpack -- about 22s of the gate after any kernel or
// webapp change.
val webappJar = tasks.register<Jar>("webappJar") {
    description = "The web application bundle, as a jar of its own" + if (useDevWebapp) " (readable development build)" else ""
    archiveClassifier.set("webapp")
    from(embedWebapp) { into("webapp") }
}

configurations.consumable("webappBundleElements") {
    outgoing.artifact(webappJar)
}

// `:appui:build` (and `assemble`) still rebuild the bundle, as the README's "after a front-end change" step relies on;
// the gate runs `test`, which does not assemble.
tasks.named("assemble") {
    dependsOn(webappJar)
}

// The tests' bundle: this module's tests read the bundle's files, not what the compiler made of them -- a `webapp.js`
// served as JavaScript and not blank, and the authored stylesheet and artwork byte for byte. So they read the
// authored files copied straight from the webapp's sources, beside the placeholder
// `src/test/resources/webapp/webapp.js`, rather than a built bundle.
val testWebapp = tasks.register<Sync>("testWebapp") {
    description = "The webapp's authored assets, for this module's tests"
    from(project(":webapp").layout.projectDirectory.dir("src/jsMain/resources")) {
        include(webappAuthoredAssets)
    }
    into(layout.buildDirectory.dir("testWebappResources/webapp"))
}

sourceSets {
    test {
        resources.srcDir(layout.buildDirectory.dir("testWebappResources"))
    }
}

tasks.named("processTestResources") {
    dependsOn(testWebapp)
}
