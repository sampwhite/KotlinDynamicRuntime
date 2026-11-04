// `launch` — the application entry point. Unlike the other modules, its Kotlin
// source lives directly in the `launch` directory rather than under a
// `src/main/kotlin` tree, and it carries no resources. Depends on `config`,
// which re-exports the base modules (common/kdn) via `api`, so this single
// dependency brings the whole configuration toolkit. This is the one allowed
// direction: `config` itself does not depend on `launch`.
import com.dynamicruntime.buildlogic.wireInjectedComponents

// Resolved outside `dependencies` so the null check reads once; see the edge note inside.
val edgeProject = findProject(":edge")
// The multi-node test harness's fixtures (issue #872), guarded for the same reason as the edge.
val multiNodeTestProject = findProject(":multiNodeTest")
// The workflow assistant (issue #1525), guarded for the same reason as the edge.
val assistantProject = findProject(":assistant")

plugins {
    id("kdr.kotlin-conventions")
    application
}

sourceSets {
    main {
        // Source code root is the `apps` subdirectory, rather than a
        // `src/main/kotlin` tree. This keeps package paths aligned with the
        // directory structure Java-style: e.g. `apps/kdn/Main.kt` -> package
        // `kdn`. Keeping the root at `apps` (rather than the whole launch
        // project dir) also keeps `build.gradle.kts` and the `build` output
        // outside the source root, so the IDE does not mistake the build
        // script for a compilable module source.
        kotlin.setSrcDirs(listOf("apps"))
        // No resources for the launch module.
        resources.setSrcDirs(emptyList<Any>())
    }
    test {
        // The committed reference copies' dump and check (issues #1603, #1607), and nothing else: they are here
        // because this module's classpath is the only one holding every module. Package `kdn`, as `apps` has it.
        kotlin.setSrcDirs(listOf("test"))
        resources.setSrcDirs(emptyList<Any>())
    }
}

dependencies {
    implementation(project(":config"))
    // The demo `sample` module. Its SampleComponent is discovered at startup via ServiceLoader (issue #171)
    // and self-gates to developer environments (SampleComponent.isLoaded), so it never enters a real
    // deployment's endpoint set. `runtimeOnly` because the launcher no longer references it at compile time --
    // it reaches it only through the versioned KdrProvider/ComponentDefinition interfaces.
    runtimeOnly(project(":sample"))
    // The webapp host: its AppUiComponent serves the self-contained front end (embedded `:webapp` bundle)
    // under the `wa` context root. Registered unconditionally in Start.kt.
    implementation(project(":appui"))
    // The KdrEdge component (issue #386). `runtimeOnly` and discovered via ServiceLoader, exactly like
    // `sample`: the launcher never references it at compile time, so nothing here names the edge.
    //
    // Guarded, because a module is only in the build if a workspace's own (non-versioned)
    // settings.gradle.kts includes it. An unguarded project(":edge") fails at CONFIGURATION time for anyone
    // who has not added that line -- their whole build refuses, not merely one file. Declaring nothing is not
    // an error here; `StartEdge` reports the absence itself, to whoever actually runs an edge.
    edgeProject?.let { runtimeOnly(project(it.path)) }
    // The multi-node test fixtures (issue #872): on the runtime classpath, so `:launch:run`, IntelliJ run
    // configurations and the pathing jar load them for development -- inert unless switched on -- but filtered
    // out of the distribution below, so a deployment never ships them.
    multiNodeTestProject?.let { runtimeOnly(project(it.path)) }
    // The workflow assistant (issue #1525): discovered via ServiceLoader like the edge, and guarded like it. It ships
    // in the distribution; it does nothing until a deployment holds a model key and an operator switches a client on.
    assistantProject?.let { runtimeOnly(project(it.path)) }
}

// The webapp bundle (issue #1076): `:appui` publishes it as a jar of its own on `webappBundleElements`, and this
// module puts it wherever the app runs or ships -- `run` and the main runtime classpath (which an IDE run through
// Gradle uses), the start scripts and the distribution, and the pathing jar -- and nowhere a test runs, so no test
// task pays for a production webapp build.
val webappBundleDeps = configurations.dependencyScope("webappBundleDeps")
val webappBundle = configurations.resolvable("webappBundle") { extendsFrom(webappBundleDeps.get()) }
dependencies {
    add(webappBundleDeps.name, project(mapOf("path" to ":appui", "configuration" to "webappBundleElements")))
}
sourceSets.main {
    runtimeClasspath += files(webappBundle)
}
tasks.named<JavaExec>("run") {
    classpath += files(webappBundle)
}
tasks.named<CreateStartScripts>("startScripts") {
    classpath = classpath!! + files(webappBundle)
}

// The deployable distribution (`installDist` / `distZip`) leaves the multi-node test module out (issue #872): a
// production node carries no fake jobs, rather than carrying them switched off.
distributions {
    main {
        contents {
            exclude("**/multiNodeTest*.jar")
            from(webappBundle) { into("lib") }
        }
    }
}

// The committed reference documents (issues #1603, #1607): each `docs/reference` file holds a copy of what its
// generator makes, between marker lines, so it can be read without a server and a change to it shows in a diff.
// `refDocsDump` rewrites the copies, touching only a file whose copy changed; `refDocsTest` fails while one is behind
// its generator. Here, because the environment variables are gathered from every module's declarations, and this
// module's classpath is the only one holding them all -- so a build leaving out an optional module refuses both
// rather than writing a list that lacks its variables. The test is a task of its own for the reason
// `:base:common:docLinksTest` is: it reads the tree, so the files are declared as its inputs.
val refDocsTestClass = "kdn.ReferenceCopiesTest"
val repoRoot = layout.projectDirectory.dir("..")
val missingModules = listOf(":edge" to edgeProject, ":multiNodeTest" to multiNodeTestProject, ":assistant" to assistantProject)
    .filter { it.second == null }.map { it.first }
fun Task.requireEveryModule() = doFirst {
    if (missingModules.isNotEmpty()) {
        throw GradleException("The reference copies are made from every module, and this build leaves out " +
            "${missingModules.joinToString()}: include them in settings.gradle.kts, as the example does.")
    }
}

val refDocsTest = tasks.register<Test>("refDocsTest") {
    description = "Checks that the committed copies of the reference documents are current."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching(refDocsTestClass)
    inputs.files(fileTree(repoRoot.dir("docs/reference")) { include("**/*.md") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
    requireEveryModule()
}

tasks.register<JavaExec>("refDocsDump") {
    description = "Rewrites the committed copies of the reference documents in docs/reference."
    group = "documentation"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("kdn.ReferenceCopiesKt")
    args(repoRoot.asFile.path)
    requireEveryModule()
}

tasks.named<Test>("test") {
    filter.excludeTestsMatching(refDocsTestClass)
    // Its only tests are the reference check's, which run in `refDocsTest`, so this task finds none, and says so.
    filter.isFailOnNoMatchingTests = false
    failOnNoDiscoveredTests = false
}

tasks.named("check") {
    dependsOn(refDocsTest)
}

// Deployment-injected providers (issue #171): custom config now, custom components later. The deployment
// declares which projects to bring in via its non-versioned settings.gradle.kts; this call adds each to the
// RUNTIME classpath (never compile) so the launcher's ServiceLoader can discover them. The versioned build
// names none of them, and declaring nothing is not an error.
wireInjectedComponents()

application {
    // The full application entry point: boots the instance and starts the HTTP server.
    // (WiringCheck remains as a separate, server-free dependency-proof entry point.)
    //
    // Selectable with `-PmainClass=` (issue #377), so `:launch:run` can start the edge launcher
    // (`kdn.StartEdgeKt`, via `bin/kdr-edge`) as well as the application one. The `run` task is the right
    // entry for both because it rebuilds and embeds the current `:webapp` bundle -- an edge serves the front
    // end too, so reaching for `bin/kdr-run` and the pathing jar instead would skip that.
    mainClass.set(providers.gradleProperty("mainClass").orElse("kdn.StartKt"))
}

// A "pathing" jar (issue #175): a manifest-only jar whose `Class-Path` lists this module's own jar plus every
// runtime dependency jar, each at its build/cache location as a `file:` URI. It is the classpath behind
// `bin/kdr-run`, which launches an arbitrary main with `java -cp <jar> <ClassName>` -- so unlike the `run`
// task, it is not tied to `application.mainClass`.
//
// Chosen over a fat jar because the dependency jars stay *separate*: there is no `META-INF/services` collision
// to reconcile -- `ServiceLoader` reads each jar's own provider file directly (H2/PostgreSQL `java.sql.Driver`,
// and our own `KdrProvider` files, all just work) -- and rebuilding after a small change only recompiles the
// affected project's jar, never a repacked archive whose manifest is otherwise identical. Because it references
// jars by absolute path, it is a local developer artifact, not a portable one; a formal, relocatable
// distribution comes from the `application` plugin's `distZip`/`installDist` (a `bin/` script plus a `lib/` of
// these same separate jars).
tasks.register<Jar>("pathingJar") {
    group = "build"
    description = "Builds a manifest-only 'pathing' jar referencing every runtime jar by path (see bin/kdr-run)."
    archiveClassifier.set("path")
    // The referenced jars must exist and be current: this module's own jar, plus (via the runtime classpath as
    // an input) every project and external dependency jar.
    dependsOn(tasks.jar)
    val runtimeClasspath = configurations.runtimeClasspath
    val bundle = files(webappBundle)
    val ownJar = tasks.jar.flatMap { it.archiveFile }
    inputs.files(runtimeClasspath)
    inputs.files(bundle)
    manifest {
        // A default Main-Class for `java -jar`; kdr-run overrides it by naming a class via `-cp <jar> <Class>`.
        attributes["Main-Class"] = application.mainClass.get()
    }
    doFirst {
        // Resolve in the task action (not at configuration time) so an unrelated build does not pay for
        // dependency resolution. Class-Path entries are space-separated `file:` URIs, so paths with spaces are
        // encoded, and the JDK handles the manifest line-wrapping of the long value.
        val jars = listOf(ownJar.get().asFile) + runtimeClasspath.get().files + bundle.files
        manifest.attributes["Class-Path"] = jars.joinToString(" ") { it.toURI().toString() }
    }
}
