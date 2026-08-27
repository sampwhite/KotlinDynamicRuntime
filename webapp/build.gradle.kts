// `webapp` — the browser front end. Unlike the other modules (plain Kotlin/JVM
// via `kdr.kotlin-conventions`), this one is a Kotlin Multiplatform module with
// a single JS/browser target. It compiles Kotlin to JavaScript, renders a React
// UI through the JetBrains kotlin-wrappers, and — because TypeScript definition
// generation is switched on below — also emits a `.d.ts` file, so any exported
// Kotlin API is consumable from TypeScript.
//
// The toolchain pin and the multiplatform Kotlin Gradle plugin come from the
// `kdr.kotlin-multiplatform-conventions` convention plugin in `build-logic`.
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    id("kdr.kotlin-multiplatform-conventions")
}

// Version of the kotlin-wrappers BOM. It aligns the React/React-DOM wrapper
// artifacts (and the npm React version they pull in) with this build's Kotlin
// version: 2026.6.10 is built against kotlin-stdlib 2.4.0 and bundles React 19.
val kotlinWrappersBom = "2026.6.10"

kotlin {
    js {
        // Build a browser application (as opposed to a Node.js one).
        browser {
            commonWebpackConfig {
                // The bundle index.html references via <script src="webapp.js">.
                outputFileName = "webapp.js"

                // Whether the dev server opens a browser itself. On by default (a plain Run of
                // jsBrowserDevelopmentRun pops Chrome at :8080). Turn OFF with `-Pwebapp.open=false` when you
                // want to attach a JS debugger: the debugger has to launch Chrome with a remote-debugging
                // port, and Chrome allows only one process per profile — so if the dev server has already
                // opened Chrome, the debugger's launch fails with IntelliJ's "Another browser process is
                // already running" dialog. Disabling the auto-open leaves the debugger as the sole opener.
                val openInBrowser = (project.findProperty("webapp.open") as? String) != "false"

                // Which backend the dev server proxies the API to. `7070` is the developer's own IntelliJ
                // instance, which is the right default and is also the one port a second session must not
                // touch -- so an agent, or anyone running a second checkout, could not use the dev server at
                // all while this was hardcoded. Override it with `-Pwebapp.backendPort=7071`.
                val backendPort = (project.findProperty("webapp.backendPort") as? String)?.toIntOrNull() ?: 7070

                // The dev-server port itself, for the same reason: two sessions cannot both hold 8080.
                val devServerPort = (project.findProperty("webapp.port") as? String)?.toIntOrNull() ?: 8080

                // Pin the webpack dev server to a fixed port. Without this,
                // Kotlin/JS defaults to 8080 and will silently hop to the next
                // free port if it's taken, giving an unpredictable URL. Reuse
                // any existing DevServer config so other settings aren't lost.
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).copy(
                    port = devServerPort,
                    // Open Chrome specifically (not the OS default browser) at the fixed
                    // http://localhost:8080/ once compiled and listening. `open` is serialized to the
                    // webpack-dev-server config via Gson, so this map becomes
                    // `open: { app: { name: "google chrome" } }` (the macOS name the `open` npm package
                    // expects). NOTE: run the Gradle task with Run, not Debug — Debug makes IntelliJ start a
                    // JavaScript Debug session that opens its OWN Chrome. For debugging, prefer Run +
                    // `-Pwebapp.open=false` (see [openInBrowser]) and attach a JS Debug config at :8080.
                    open = if (openInBrowser) mapOf("app" to mapOf("name" to "google chrome")) else false,
                    // Same-origin dev: proxy the runtime's context roots to the backend (see [backendPort]).
                    // The browser then makes same-origin calls to the dev server, which forwards them — so no
                    // CORS handling is needed (the runtime's HTTP server has none). Start the backend with
                    // `./gradlew :launch:run` first.
                    //   "/kda" — the API context root (endpoints).
                    //   "/st"  — the static context root: Markdown fragments (a group's copy) and whole
                    //            Markdown documents. In production these are same-origin already; only the
                    //            dev server needs to be told.
                    proxy = mutableListOf(
                        KotlinWebpackConfig.DevServer.Proxy(
                            context = mutableListOf("/kda", "/st"),
                            target = "http://localhost:$backendPort",
                        ),
                    ),
                )
            }

        }
        // Also, register the Node.js environment on this same JS target. The app itself is a browser bundle
        // (the `browser {}` block above), but the frontend's *pure-logic* unit tests (issue #161) — the
        // UiConfig→typed-config mappers, TraceId, Copy — touch no DOM and run far cheaper under Node than in
        // a headless-browser Karma run. Declaring `nodejs()` gives us the `jsNodeTest` task; the browser test
        // task is disabled below so `check` never pulls in a headless Chrome. This leaves every browser
        // artifact (the dev server, `jsBrowserDistribution`, appui's embedded bundle) untouched.
        nodejs()

        // Produce an executable JS bundle (entry point = `main()`), not just a
        // library — this is what wires up the webpack tasks that download the
        // npm modules (react, react-dom, …) and bundle the app.
        binaries.executable()

        // "Turn Kotlin into TypeScript": emit `.d.ts` declarations for every
        // `@JsExport`-annotated declaration. Output lands next to the JS bundle
        // under build/dist/js/productionExecutable (and the kotlin/ compile dir).
        generateTypeScriptDefinitions()
    }

    sourceSets {
        getByName("jsMain") {
            dependencies {
                // The shared multiplatform kernel (issue #56): JSON/date/string utilities, the template
                // evaluator, and the JSON-Schema model/parser/validator, compiled to JS. Wired now so the
                // frontend depends on the same code as the backend; later issues replace the webapp's
                // hand-rolled schema/constant duplicates with these.
                implementation(project(":base:kernel"))

                // The BOM governs the versions of every kotlin-wrappers artifact
                // below, so they are declared without explicit versions.
                implementation(project.dependencies.platform("org.jetbrains.kotlin-wrappers:kotlin-wrappers-bom:$kotlinWrappersBom"))

                // React + the DOM renderer. These transitively declare their npm
                // counterparts (react / react-dom), which Gradle's Kotlin/JS
                // tooling downloads into the build automatically.
                implementation("org.jetbrains.kotlin-wrappers:kotlin-react")
                implementation("org.jetbrains.kotlin-wrappers:kotlin-react-dom")

                // Ant Design — a plain npm React component library. It has no
                // official Kotlin wrappers, so it's pulled in as an npm module
                // and consumed through the handwritten `external` declarations
                // in AntdComponents.kt. antd is CSS-in-JS, so no CSS import
                // is needed in index.html.
                implementation(npm("antd", "6.5.0"))

                // dayjs — antd's date type. Its DatePicker takes and returns a Dayjs, not a string, so
                // binding a date field to the form's (string) value means converting at that boundary. antd
                // already depends on it, but it is declared here because we import it directly: relying on a
                // transitive package happening to be hoisted is the kind of thing that breaks on an unrelated
                // dependency bump. The range matches what antd resolves, so no second copy is installed.
                implementation(npm("dayjs", "^1.11.11"))

                // Coroutines back every suspend-based API call, which uses the browser Fetch API directly
                // (see `Http.kt`) — no HTTP-client library. Its version is governed by the kotlin-wrappers
                // BOM above.
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
            }
        }
        // The frontend's pure-logic test suite (issue #161). Multiplatform `kotlin.test`, the same framework
        // `base:kernel`'s commonTest uses, so the assertions read identically across the two modules. These
        // tests call pure functions directly (no React, no fetch, no DOM) and run under Node via `jsNodeTest`.
        getByName("jsTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// The app is a browser bundle, but its unit tests are pure logic and run under Node (`jsNodeTest`). Disable
// the browser test task so `check`/`build` never requires a headless Chrome (issue #161). Re-enable this if a
// real in-browser (DOM/React) test suite is ever added -- this line is that suite's starting point, and the
// case for it is recorded at `deferred-work.md#when-a-frontend-change-breaks-a-page-its-author-did-not-open`.
// (#161 delivered the pure-logic `jsTest` layer and is closed; it does not track the browser half.)
tasks.named("jsBrowserTest") { enabled = false }
