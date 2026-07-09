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

## Layout

```
base/common      # foundational module (com.dynamicruntime.common)
base/kdn         # dynamic-runtime core, depends on common (com.dynamicruntime.kdn)
launch           # application entry points; source root is launch/apps (package roots there)
build-logic      # included build providing the kdr.kotlin-conventions plugin
examples         # templates a deployment copies into the workspace directory
```

Module dependencies: `base/kdn` → `base/common`; `launch` → `base/common` + `base/kdn`.

## Building

This project builds with Gradle (Kotlin DSL) on a JDK 25 toolchain. Shared
build configuration — the Kotlin plugin/version, repositories, the JVM
toolchain, and the Kotest test stack — lives in the `kdr.kotlin-conventions`
convention plugin under `build-logic/`, so each module's build script only
declares `plugins { id("kdr.kotlin-conventions") }` plus its own dependencies.

### The settings file is supplied per-deployment

By design, `settings.gradle.kts` is **not** part of this repository. It is
provided in the workspace directory — the directory that *contains* this one — so
that a single Gradle build can compose source from multiple repositories for a
given deployment. A ready-to-adapt template is provided at
[`examples/settings.gradle.kts.example`](examples/settings.gradle.kts.example):
copy it to the workspace directory, rename it to `settings.gradle.kts`, and adjust
as needed. The workspace directory is also where the (deployment-specific) Gradle
invocation runs.

A copy of the Gradle wrapper is included here for convenience.

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
