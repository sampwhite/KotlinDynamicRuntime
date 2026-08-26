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
installation options as they are added.

## Layout

```
base/kernel      # multiplatform (JVM + JS) code shared by backend and front end
base/common      # foundational JVM module (com.dynamicruntime.common)
base/kdn         # dynamic-runtime core, depends on common (com.dynamicruntime.kdn)
config           # configuration builders; re-exports the base modules (com.dynamicruntime.config)
launch           # application entry points; source root is launch/apps (package roots there)
sample           # demo app (file upload/download endpoints) the launcher loads in developer environments
webapp           # Kotlin/JS + React (antd) front end (the browser bundle)
appui            # JVM host that serves the webapp bundle under the /wa context root
bin              # convenience command-line scripts (see Command-line scripts below)
build-logic      # included build providing the kdr.kotlin-conventions convention plugins
examples         # templates a deployment copies into the workspace directory
```

Module dependencies: `base/common` → `base/kernel` (JVM variant); `base/kdn` → `base/common`;
`config` → `base/common` + `base/kdn`; `sample` → `config`; `appui` → `config`;
`launch` → `config` + `sample` + `appui`; `webapp` → `base/kernel` (JS variant).

**`base/kernel` is the one worth knowing about.** Its `commonMain` holds pure, transpile-safe Kotlin — no
`java.*`, no reflection — including the universal exception, the JSON/date/string utilities, the template
evaluator, and the **JSON-Schema model with its parser and validator**. The backend depends on its JVM
variant and the front end on its JS variant, so both run *the same* validation code rather than two
implementations that agree until they do not. Code moved there keeps its original
`com.dynamicruntime.common.*` package, so relocating a file changes no call site.

## Building

This project builds with Gradle (Kotlin DSL) on a JDK 25 toolchain. Shared
build configuration — the Kotlin plugin/version, repositories, the JVM
toolchain, and the Kotest test stack — lives in the `kdr.kotlin-conventions`
convention plugin under `build-logic/`, so each module's build script only
declares `plugins { id("kdr.kotlin-conventions") }` plus its own dependencies.

### The settings file is supplied per-deployment

By design, `settings.gradle.kts` is **not** part of this repository. It is
provided in the **workspace directory** (the directory that *contains* this one),
so that a single Gradle build can compose source code from multiple repositories for a
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

  **When a front-end crash is unreadable, add `-Pwebapp.dev=true`.** The production bundle is minified, so a
  Kotlin exception arrives with no message and a mangled name — a caught render failure reports itself as
  `ji` at a byte offset. That flag embeds the *readable* build in its place:

  ```sh
  ./gradlew :launch:run -Pwebapp.dev=true    # same URL, same behavior, legible stack traces
  ```

  The same crash then names the Kotlin that failed (`IllegalStateException … at SchemaForm$lambda`). The app
  bar shows a quiet **readable build** badge, so it is obvious which bundle a tab is running. It costs about
  24 MB of bundle rather than 2 MB and a slower first load, so it is a troubleshooting build, not a default —
  and it is one build *or* the other, never both, because Kotlin/JS runs the two executable modes through a
  single compile-sync directory.

  **The app can also be told to fail**, which is the other half of diagnosing it. A small set of debug pages
  exists wherever the deployment permits it (a test instance), reached by URL so a browser test can drive them
  with nothing but a link:

  ```
  #page=debug                 what the debug area offers
  #page=debug&tool=state      the resolved app config and refresh generation this tab is running on
  #page=debug&tool=fault      throws while rendering, so the page error boundary is seen to catch
  #<any page>&fault=shell     throws in the app bar, so the backstop boundary is seen to catch
  ```

  A render failure never blanks the page: an error boundary swaps in a panel inside the shell, leaving the
  navigation usable, and a second boundary behind it catches a failure in the shell itself. Paired with
  `-Pwebapp.dev=true`, a deliberate fault reports the Kotlin declaration that threw rather than a byte offset.
  On a real deployment the debug routes resolve to the home page — they do not exist rather than being refused.

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

## Command-line scripts

`bin/` holds the deployment's command-line tools. `kdr-install` offers to put them on your `PATH`; otherwise
invoke them by path. `kdr-help` lists them with these same one-line descriptions.

```
kdr-install        Idempotently set up or update this deployment to run the kdr commands.
kdr-backend        Run the backend server (StartKt) via :launch:run.
kdr-webapp         Start the Kotlin/JS webapp dev server on http://localhost:8080.
kdr-tests          Run every module's tests (the `check` task across every subproject).
kdr-probe          Drive a running instance as a chosen caller: scenarios, or a single call.
kdr-run            Launch any Kotlin main class from the project's runtime classpath.
kdr-create-config  Scaffold the customConfig provider project and wire it into settings.gradle.kts.
kdr-source-dirs    Regenerate the source-directory manifest (current-source-directories.txt).
kdr-help           List the kdr commands (and shell functions) with one-line descriptions.
```

`kdr-use` is a shell *function* rather than a script (it changes your shell's `PATH` and workspace, which a
subprocess cannot do); `kdr-install` can add it to your shell's rc file.

Command-line tooling is written in **Kotlin, not shell**: `kdr-run` launches any main class from the runtime
classpath, and each `bin/` script above it is a thin wrapper that does no deciding. See
[`code-guide.md`](code-guide.md) for why.

## Testing

```sh
./gradlew check        # the whole suite; bin/kdr-tests runs exactly this
```

Use `check`, not `test`. The multiplatform modules (`base/kernel`, `webapp`) have no `test` task at all — they
expose `jvmTest` / `jsNodeTest` — and Gradle runs a named task wherever it exists while saying nothing about
the projects lacking it, so `test` leaves those modules' results silently absent.

`kdr-probe` drives a **running** instance as a chosen caller, for the checks a unit test cannot make — real
cookies, the real dispatcher, live roles. Run it with no arguments to list its scenarios.

## Conventions

See [`code-guide.md`](code-guide.md). In brief: Kotlin everywhere, minimal
reflection, explicit Map-based serialization, lowerCamelCase constants wrapped
in upper-cased acronym objects (always referenced qualified), JSON-schema-driven
configuration, a single universal exception (`KdrException`) and context
(`KdrCxt`) type, and synchronous code on virtual threads rather than coroutines in the backend.

## Configuration

Application behavior is varied at startup largely through environment variables (a deliberate choice
explained in [`code-guide.md`](code-guide.md)). Each variable is **declared once in code** as an `EnvVarDef`
value — carrying its name, default, and documentation — so the declaration is the reference: the read path
takes a declared def, so a variable nobody declared cannot even be read. Browse the declarations grouped by
area (`DbEnv`, `NodeUtil`, `LogSetup`, and their neighbours). A running node also serves the live set — each
variable and the value it actually resolved to *on that node* — at the operator endpoint
`/operator/env/reference`, shown as the **Environment** view in the app.

## The Gedra design documents

[`gedra-entry.md`](gedra-entry.md) describes the **Gedra entry** — the universal stored entity the next phase
of the project is built around — together with the JSON-schema constructs it needs (a declared discriminator,
`if`/`then`/`else`, `g-primaryKey`, `g-derived`). It was written before any of it existed, as a statement of
intent to design against, and it records what was deliberately left open or postponed. The schema constructs
have since been built; `g-primaryKey` and the entity store itself have not.

[`gedra-config-and-data.md`](gedra-config-and-data.md) is its companion, about everything *around* a single
entry: how a gedra is identified, how a deployment can be split by client, what one client's definitions may
see of another's, and what happens when two definitions disagree. It marks each rule as built or intended,
because it describes a subsystem partway through being written.

[`gedra-patch.md`](gedra-patch.md) covers the remaining verb: how a stored gedra is **changed**. Creating,
reading, listing and deleting exist; changing does not, and it is where the difficulty concentrates — locked
and process-owned entries, merges of partial data, edits that span several documents and several data kinds,
and a primary key that lives inside the data it identifies. Like `gedra-entry.md` it was written before the
code, and it records why decisions changed as well as what they became.

[`client-definition.md`](client-definition.md) describes the **client** — the thing whose id sits in every
`GedraId`, and which turns out to own more than an identifier: which environments it is enabled in, which
traits it supports, what its callers are shown, and what happens to its content when it is not there. Like
the others it was written before the code, and it is grown as decisions settle rather than restated.

## Deferred work

Work we have deliberately put off — each with the condition that should make us revisit it — lives in
[`deferred-work.md`](deferred-work.md), **not** in the issue tracker. The tracker stays a queue of things to
act on soon; deferred items become issues only once their trigger fires. Consult and append to that file when
you defer something rather than leaving a "someday" issue open.

## License

[MIT](LICENSE)
