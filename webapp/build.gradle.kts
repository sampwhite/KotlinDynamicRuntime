// `webapp` — the browser front end. Unlike the other modules (plain Kotlin/JVM
// via `kdr.kotlin-conventions`), this one is a Kotlin Multiplatform module with
// a single JS/browser target. It compiles Kotlin to JavaScript, renders a React
// UI through the JetBrains kotlin-wrappers, and — because TypeScript definition
// generation is switched on below — also emits a `.d.ts` file so any exported
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

                // Pin the webpack dev server to a fixed port. Without this,
                // Kotlin/JS defaults to 8080 and will silently hop to the next
                // free port if it's taken, giving an unpredictable URL. Reuse
                // any existing DevServer config so other settings aren't lost.
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).copy(
                    port = 8080,
                    // Open Chrome specifically (not the OS default browser) at the fixed
                    // http://localhost:8080/ once compiled and listening. `open` is serialized to the
                    // webpack-dev-server config via Gson, so this map becomes
                    // `open: { app: { name: "google chrome" } }` (the macOS name the `open` npm package
                    // expects). NOTE: run the Gradle task with Run, not Debug — Debug makes IntelliJ start a
                    // JavaScript Debug session that opens its OWN Chrome. For debugging, prefer Run +
                    // `-Pwebapp.open=false` (see [openInBrowser]) and attach a JS Debug config at :8080.
                    open = if (openInBrowser) mapOf("app" to mapOf("name" to "google chrome")) else false,
                    // Same-origin dev: proxy the runtime's context roots to the backend on :7070. The browser
                    // then makes same-origin calls to the dev server, which forwards them — so no CORS
                    // handling is needed (the runtime's HTTP server has none). Start the backend with
                    // `./gradlew :launch:run` first.
                    //   "/kda" — the API context root (endpoints).
                    //   "/st"  — the static context root: Markdown fragments (a group's copy) and whole
                    //            Markdown documents. In production these are same-origin already; only the
                    //            dev server needs to be told.
                    proxy = mutableListOf(
                        KotlinWebpackConfig.DevServer.Proxy(
                            context = mutableListOf("/kda", "/st"),
                            target = "http://localhost:7070",
                        ),
                    ),
                )
            }

        }
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
                // and consumed through the hand-written `external` declarations
                // in AntdComponents.kt. antd is CSS-in-JS, so no CSS import
                // is needed in index.html.
                implementation(npm("antd", "6.5.0"))

                // Coroutines back the suspend-based Todo calls, which use the browser Fetch API directly
                // (see TodoApi.kt) to hit the `:sample` runtime's endpoints — no HTTP-client library. Its
                // version is governed by the kotlin-wrappers BOM above.
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
            }
        }
    }
}
