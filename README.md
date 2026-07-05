# KotlinDynamicRuntime

Dynamic schema and config: a Kotlin reimplementation of a dynamic,
schema-driven application runtime. The proposal for this project can be found at
[Kotlin, KMP, JSON Schema, SDUI, and Claude — the AI's rewrite](https://gyassa.com/personal/kdr-project-ai.html).
The coding philosophy and conventions for the project are described in [`code-guide.md`](code-guide.md).

## Layout

```
base/common      # foundational module (com.dynamicruntime.common)
base/kdn         # dynamic-runtime core, depends on common (com.dynamicruntime.kdn)
launch           # application entry points; source root is launch/apps (package roots there)
build-logic      # included build providing the kdr.kotlin-conventions plugin
examples         # templates a deployment copies into the parent directory
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
provided in the directory that *contains* this one, so that a single Gradle
build can compose source from multiple repositories for a given deployment.
A ready-to-adapt template is provided at
[`examples/settings.gradle.kts.example`](examples/settings.gradle.kts.example):
copy it to the parent directory, rename it to `settings.gradle.kts`, and adjust
as needed. The same parent directory is also where the (deployment-specific)
Gradle invocation runs.

A copy of the Gradle wrapper is included here for convenience.

## Conventions

See [`code-guide.md`](code-guide.md). In brief: Kotlin everywhere, minimal
reflection, explicit Map-based serialization, lowerCamelCase constants wrapped
in upper-cased acronym objects (always referenced qualified), JSON-schema-driven
configuration, a single universal exception (`KdrException`) and context
(`KdrCxt`) type, and synchronous code on virtual threads rather than coroutines.

## License

[MIT](LICENSE)
