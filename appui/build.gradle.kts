// `appui` — the JVM host that serves the self-contained webapp. It contributes no endpoints; its
// AppUiService is a ContentServer (registered with the runtime's request dispatcher) that serves the browser
// bundle under the `wa` context root. The Kotlin/JS front end itself lives in `:webapp`; this module embeds
// that module's *production* bundle as classpath resources, so the running server — or a packaged jar — can
// serve it with no separate webpack dev server and no API proxy (calls are same-origin with the runtime).
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
val embedWebapp = tasks.register<Copy>("embedWebapp") {
    description = "Embed the web application"
    dependsOn(":webapp:jsBrowserDistribution")
    from(project(":webapp").layout.buildDirectory.dir("dist/js/productionExecutable")) {
        include("webapp.js", "webapp.js.map", "app.css", "favicon.svg", "brand-mark.svg")
    }
    into(layout.buildDirectory.dir("webappResources/webapp"))
}

sourceSets {
    main {
        // The embedded bundle joins this module's resources, so it lands on the runtime classpath.
        resources.srcDir(layout.buildDirectory.dir("webappResources"))
    }
}

tasks.named("processResources") {
    dependsOn(embedWebapp)
}
