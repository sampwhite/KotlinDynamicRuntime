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

                // Pin the webpack dev server to a fixed port. Without this,
                // Kotlin/JS defaults to 8080 and will silently hop to the next
                // free port if it's taken, giving an unpredictable URL. Reuse
                // any existing DevServer config so other settings aren't lost.
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).copy(
                    port = 8080,
                    // Let the dev server itself open the browser at the correct
                    // URL (http://localhost:8080/) once it's compiled and
                    // listening. This avoids relying on IntelliJ's Debug
                    // launcher, which opens a random ephemeral port.
                    open = true,
                    // Same-origin dev: proxy the API context root ("/kda") to the `:sample` runtime server
                    // on :7070. The browser then makes same-origin calls to the dev server, which forwards
                    // them to the API — so no CORS handling is needed (the runtime's HTTP server has none).
                    // Start the API with `./gradlew :sample:run` before using the Todo list.
                    proxy = mutableListOf(
                        KotlinWebpackConfig.DevServer.Proxy(
                            context = mutableListOf("/kda"),
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
