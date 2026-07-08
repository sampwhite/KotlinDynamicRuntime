# KotlinDynamicRuntime

Dynamic schema and config: a Kotlin reimplementation of a dynamic,
schema-driven application runtime. The proposal for this project can be found at
[Kotlin, KMP, JSON Schema, SDUI, and Claude — the AI's rewrite](https://gyassa.com/personal/kdr-project-ai.html).
The coding philosophy and conventions for the project are described in [`code-guide.md`](code-guide.md).

## Getting started

Setup is bootstrapped by an idempotent installer, `bin/kdr-install`. It checks that a JDK is present,
creates the per-deployment configuration (`settings.gradle.kts` and `gradle.properties`) from the templates
if they are missing, and offers to add the command-line scripts to your `PATH`.

Run it from the directory that **contains** your `KotlinDynamicRuntime` checkout — the deployment root (the
parent directory, where the per-deployment `settings.gradle.kts` lives):

```sh
cd /path/to/deployment-root        # the directory that holds KotlinDynamicRuntime/
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
webapp           # Kotlin/JS + React (antd) front end
bin              # convenience command-line scripts (kdr-install, kdr-run, ...)
build-logic      # included build providing the kdr.kotlin-conventions convention plugins
examples         # templates a deployment copies into the parent directory
```

Module dependencies: `base/kdn` → `base/common`; `config` → `base/common` + `base/kdn`;
`sample` → `config`; `launch` → `config` + `sample`.

## Building

This project builds with Gradle (Kotlin DSL) on a JDK 25 toolchain. Shared
build configuration — the Kotlin plugin/version, repositories, the JVM
toolchain, and the Kotest test stack — lives in the `kdr.kotlin-conventions`
convention plugin under `build-logic/`, so each module's build script only
declares `plugins { id("kdr.kotlin-conventions") }` plus its own dependencies.

### The settings file is supplied per-deployment

By design, `settings.gradle.kts` is **not** part of this repository. It is
provided in the directory that *contains* this one, so that a single Gradle
build can compose source from multiple repositories for a given deployment.
A ready-to-adapt template is provided at
[`examples/settings.gradle.kts.example`](examples/settings.gradle.kts.example);
`bin/kdr-install` copies it into the parent directory as `settings.gradle.kts` for you (or copy it by hand
and adjust as needed). The same parent directory is also where the (deployment-specific) Gradle invocation
runs.

The repository ships the canonical Gradle wrapper (`gradlew` and the `gradle/` directory). Because Gradle
runs from the parent directory, that parent needs its own copy: `bin/kdr-install` copies the wrapper up when
it is missing, and — if the repository's Gradle version later changes — offers to update the parent's copy to
match.

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

## License

[MIT](LICENSE)
