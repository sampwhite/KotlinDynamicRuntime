# KotlinDynamicRuntime

Dynamic schema and config: a Kotlin reimplementation of a dynamic,
schema-driven application runtime. The proposal for this project can be found at
[Kotlin, KMP, JSON Schema, SDUI, and Claude — the AI's rewrite](https://gyassa.com/personal/kdr-project-ai.html).
The coding philosophy and conventions for the project are described in [`code-guide.md`](code-guide.md).

## The workspace

Two terms recur throughout this project and its documentation. The **workspace directory** is the directory
that *contains* this repository — the (non-versioned) directory that holds the per-deployment
`settings.gradle.kts`, the Gradle wrapper, the deployment configuration, and the runtime data (`h2Database/`,
`private/`, `logs/`). The **workspace** is the whole ensemble: the code and data under that directory that
together constitute a running deployment.

Tooling finds the workspace directory by walking up from its own location to the nearest ancestor that holds
a `settings.gradle.kts`; it can be set explicitly with the `KDR_WORKSPACE_DIR` environment variable (see
[Configuration](#configuration)).

## Getting started

Setup is bootstrapped by an idempotent installer, `bin/kdr-install`. It checks that a JDK is present,
creates the per-deployment configuration (`settings.gradle.kts` and `gradle.properties`) from the templates
if they are missing, and offers to add the command-line scripts to your `PATH`.

Run it from the **workspace directory** — the directory that **contains** your `KotlinDynamicRuntime`
checkout, where the per-deployment `settings.gradle.kts` lives:

```sh
cd /path/to/workspace              # the workspace directory, which holds KotlinDynamicRuntime/
./KotlinDynamicRuntime/bin/kdr-install
```

`kdr-install` is safe to re-run at any time — it only changes what needs changing. Re-run it to sync new
configuration (for example, projects a newer `settings.gradle.kts.example` introduces) or to pick up new
install options as they are added.

## Layout

```
base/common      # foundational module (com.dynamicruntime.common)
base/kdn         # dynamic-runtime core, depends on common (com.dynamicruntime.kdn)
config           # configuration builders; re-exports the base modules (com.dynamicruntime.config)
launch           # application entry points; source root is launch/apps (package roots there)
sample           # demo app (Todo endpoints) the launcher loads in developer environments
webapp           # Kotlin/JS + React (antd) front end (the browser bundle)
appui            # JVM host that serves the webapp bundle under the /wa context root
bin              # convenience command-line scripts (kdr-install, kdr-run, ...)
build-logic      # included build providing the kdr.kotlin-conventions convention plugins
examples         # templates a deployment copies into the workspace directory
```

Module dependencies: `base/kdn` → `base/common`; `config` → `base/common` + `base/kdn`;
`sample` → `config`; `appui` → `config`; `launch` → `config` + `sample` + `appui`.

## Building

This project builds with Gradle (Kotlin DSL) on a JDK 25 toolchain. Shared
build configuration — the Kotlin plugin/version, repositories, the JVM
toolchain, and the Kotest test stack — lives in the `kdr.kotlin-conventions`
convention plugin under `build-logic/`, so each module's build script only
declares `plugins { id("kdr.kotlin-conventions") }` plus its own dependencies.

### The settings file is supplied per-deployment

By design, `settings.gradle.kts` is **not** part of this repository. It is
provided in the **workspace directory** (the directory that *contains* this one),
so that a single Gradle build can compose source from multiple repositories for a
given deployment. A ready-to-adapt template is provided at
[`examples/settings.gradle.kts.example`](examples/settings.gradle.kts.example);
`bin/kdr-install` copies it into the workspace directory as `settings.gradle.kts` for you (or copy it by hand
and adjust as needed). The workspace directory is also where the (deployment-specific) Gradle invocation
runs.

The repository ships the canonical Gradle wrapper (`gradlew` and the `gradle/` directory). Because Gradle
runs from the workspace directory, that directory needs its own copy: `bin/kdr-install` copies the wrapper up
when it is missing, and — if the repository's Gradle version later changes — offers to update the workspace
copy to match.

### The webapp (front end)

The `webapp` module compiles Kotlin/JS + React into a browser bundle. There are two ways to run it, both
talking to the same runtime API on `:7070`:

- **Served by the runtime (self-contained).** The `appui` module embeds `webapp`'s *production* bundle as a
  classpath resource (a Gradle task copies `:webapp:jsBrowserDistribution`'s output into `appui`'s resources)
  and serves it as a content server under the `wa` context root. Nothing extra to run: building or running the
  app builds and embeds the bundle automatically, so

  ```sh
  ./gradlew :launch:run          # boots the server on :7070; the bundle is built and embedded as part of this
  ```

  then open `http://localhost:7070/wa` — also reachable from the **Webapp** link in the endpoint portal at
  `http://localhost:7070/cp/portal`. Because the page is served same-origin with the API, the webapp's
  relative `/kda/...` calls reach the runtime directly (no CORS, no proxy). After a front-end change, rebuild
  (`./gradlew :launch:run` or `:appui:build`) and hard-reload the page — the embedded bundle is a build
  artifact, so there is no hot reload on this path.

- **Webpack dev server (iterative development).** For live reload and browser debugging, run the dev server on
  `:8080`, which proxies `/kda` to the runtime on `:7070`:

  ```sh
  ./gradlew :launch:run                           # the API (and /wa) on :7070
  ./gradlew :webapp:jsBrowserDevelopmentRun       # the dev server on :8080, proxying /kda -> :7070
  ```

  This uses the development (unminified) bundle with hot reload. See the IntelliJ setup below for attaching a
  JS debugger.

### Running and debugging in IntelliJ

For the IntelliJ run configurations that launch the server and the `webapp`
front end — and the setup for debugging both the JVM server and the browser
front end at once — see [`examples/intellij-dev-setup.md`](examples/intellij-dev-setup.md).

## Conventions

See [`code-guide.md`](code-guide.md). In brief: Kotlin everywhere, minimal
reflection, explicit Map-based serialization, lowerCamelCase constants wrapped
in upper-cased acronym objects (always referenced qualified), JSON-schema-driven
configuration, a single universal exception (`KdrException`) and context
(`KdrCxt`) type, and synchronous code on virtual threads rather than coroutines.

## Configuration

Application behavior is varied at startup largely through environment variables (a deliberate choice
explained in [`code-guide.md`](code-guide.md)). The complete set — application, logging, database, and
workspace variables — is documented in [`environment-variables.md`](environment-variables.md).

## License

[MIT](LICENSE)
