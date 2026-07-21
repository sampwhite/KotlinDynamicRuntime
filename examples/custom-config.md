# Example: a deployment-supplied custom configuration module

A deployment can inject its own configuration code, which the launcher discovers
by **reflection** at startup and applies to the application config. This is the
mechanism behind the code-guide's "a deployment agent can add Kotlin code
specific to the deployment and have it picked up by the build."

This file is a guide, not live code — copy the snippets into real files in your
(non-versioned) deployment directory.

## How it works

- `config` defines `interface AppConfigApplier { fun AppConfigBuilder.applyAppConfig() }`.
- The launcher builds an `AppConfigBuilder`, finds the deployment's config object
  by name via reflection, casts it to `AppConfigApplier`, and invokes it. The
  method's receiver is the builder, so the body reads as a Kotlin builder DSL.
- The deployment provides a Gradle project named **`:customConfig`** (living
  outside the version-controlled source tree). The versioned `launch` build looks
  for that conventionally-named project and, when present, adds it to its
  **runtime** classpath automatically — so `launch` never references it at compile
  time, and its absence is not an error.

## What you create

In the directory that *contains* `KotlinDynamicRuntime/` (the same directory as
your `settings.gradle.kts`):

```
customConfig/
├── build.gradle.kts
└── apps/
    └── KdrConfig.kt
```

### `customConfig/build.gradle.kts`

Mirrors the `launch` technique — the `apps` subdirectory is the Kotlin source
root directly, so the build script and build output stay outside it. It only
needs `:config`, which re-exports the base modules (`common`/`kdn`) via `api`.

```kotlin
plugins {
    id("kdr.kotlin-conventions")
}

sourceSets {
    main {
        kotlin.setSrcDirs(listOf("apps"))
        resources.setSrcDirs(emptyList<Any>())
    }
    test {
        kotlin.setSrcDirs(emptyList<Any>())
        resources.setSrcDirs(emptyList<Any>())
    }
}

dependencies {
    implementation(project(":config"))
}
```

### `customConfig/apps/KdrConfig.kt`

The file sits directly in the `apps` source root, so it is in the **default
package** and is discovered by the bare name `"KdrConfig"`. Implement
`AppConfigApplier`; inside the override, the `AppConfigBuilder` is the implicit
receiver:

```kotlin
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.config.AppConfigApplier
import com.dynamicruntime.config.AppConfigBuilder

object KdrConfig : AppConfigApplier {
    override fun AppConfigBuilder.applyAppConfig() {
        env = ENV.prod
        inMemoryOnly = false
        data["featureX"] = true
    }
}
```

## What you add to `settings.gradle.kts`

Two lines in your top-level (non-versioned) `settings.gradle.kts` are all that is
required — defining the project is enough; `launch` wires the runtime dependency
itself:

```kotlin
include("customConfig")
project(":customConfig").projectDir = file("customConfig")
```

That's it. No edits to `launch` or any version-controlled build file are needed.

## Branding the webapp

A deployment can serve its own artwork — favicon, logo — without forking
`:webapp`. Name a classpath directory with the `appUiBrandingDir` config key and
ship the files there:

```kotlin
object KdrConfig : AppConfigApplier {
    override fun AppConfigBuilder.applyAppConfig() {
        data["appUiBrandingDir"] = "acmeBranding"
    }
}
```

`customConfig` is already on the runtime classpath, so it is the natural carrier
— but **the example build script above disables resources**, so re-enable them:

```kotlin
sourceSets {
    main {
        kotlin.setSrcDirs(listOf("apps"))
        resources.setSrcDirs(listOf("resources"))   // was emptyList()
    }
    // ...
}
```

Then place the files under that source root, in a directory matching the key:

```
customConfig/
├── apps/KdrConfig.kt
└── resources/
    └── acmeBranding/
        ├── favicon.svg          # tab icon (heavier strokes, for 16px)
        ├── brand-mark.svg       # the app bar logo and home hero
        ├── favicon-32.png       # tab-icon fallback
        ├── apple-touch-icon.png # 180×180, opaque — iOS home screen
        └── favicon.ico          # legacy
```

Notes:

- **Every file is optional.** Resolution is per asset, so you can override just
  `brand-mark.svg` and inherit the rest. Anything you omit falls back to the
  built-in.
- **You override a filename, not the shell markup.** The shell links each asset
  by its stable name (`favicon.svg`), but since issue #137 the served URL carries
  a content hash of the *served* bytes (`/wa/favicon.svg:<hash>`). So branding an
  asset gives it its own hashed URL automatically — you never touch the `<link>`
  tags, and immutable caching can never serve a stale built-in in a branded
  asset's place.
- **Watch the startup log.** A directory that overrides nothing logs a warning:
  the usual cause is a typo or resources not reaching the classpath, and without
  the warning it just looks like the branding "didn't take" (the app serves its
  built-in set and appears fine).
- **Production only.** The webpack dev server serves `:webapp`'s own resources,
  so it always shows the built-in set. Only the deployed app (`appui`) is branded.
- **The stylesheet is not brandable.** Replacing `app.css` wholesale would fork
  it and re-create the drift a single sheet exists to prevent; theming wants CSS
  variables instead.

## For Claude: controlling your own config while testing (issue #152)

*This section is addressed to Claude (the coding agent), not to a human deployer.* The rest of this file is
about a deployment shipping production config; here the same mechanism is a **test harness** — a way for you to
set instance config on a server you boot to verify a change.

The reason to use it: when a behavior is gated on a config value that has **no environment variable** — a UI
tuning value like an idle-refresh interval, or any `AppConfigBuilder` property — you would otherwise have no
way to set it for a live run. `KDR_CUSTOM_CONFIG` lets you select a config object *by name*, so you provide
your **own** object rather than editing the developer's `KdrConfig`. That isolation is the whole point: a value
the developer sets for their own run can't break yours, your edits can't disturb theirs, and neither of you
touches the other's file. (This is the same collision that once broke a live test and motivated the split.)

**First, the one-time setup — the `customConfig` project may not exist in your workspace yet.** It, and the two
`settings.gradle.kts` lines that wire it, are non-versioned, so every workspace has its own; a fresh one
(Eva's, say) has neither the project nor a `KdrConfig`. Two cases:

- **No `customConfig` project yet** (no `customConfig/` beside your `settings.gradle.kts`, or no
  `include("customConfig")` in it): create it first, exactly as **"What you create"** and **"What you add to
  `settings.gradle.kts`"** above describe — but you only need your own `ClaudeConfig.kt` in `apps/`. Do **not**
  add a `KdrConfig`; that object is the developer's to create, not yours.
- **It already exists** (the developer uses `KdrConfig`): just add `ClaudeConfig.kt` beside theirs, and leave
  `KdrConfig` alone.

Either way the file is non-versioned, so it never lands in a PR:

```kotlin
// customConfig/apps/ClaudeConfig.kt   (default package → the reflective name is just "ClaudeConfig")
import com.dynamicruntime.config.AppConfigApplier
import com.dynamicruntime.config.AppConfigBuilder

@Suppress("unused")
object ClaudeConfig : AppConfigApplier {
    override fun AppConfigBuilder.applyAppConfig() {
        // Any AppConfigBuilder property (env, inMemoryOnly, validateResponseSchema, idleBumpIntervalMs, …).
        idleBumpIntervalMs = 3000            // e.g. a 3s idle bump, to observe it without the one-minute wait
        // A key without a typed property yet: set it straight into the config map.
        data["someFutureKey"] = true
    }
}
```

Select it when you boot your server (see the `kdr-testing` skill for the port/in-memory conventions):

```bash
cd "$KDR_WORKSPACE_DIR" && \
  KDR_PORT=7071 KDR_IN_MEMORY_ONLY=true KDR_CUSTOM_CONFIG=ClaudeConfig ./gradlew :launch:run > /tmp/srv.log 2>&1 &
```

(`$KDR_WORKSPACE_DIR` is the workspace root — set it, or resolve it by walking up to the nearest
`settings.gradle.kts`, as the `kdr-testing` skill shows. It is never a fixed path.)

The values become instance config exactly as a deployment's would, so an endpoint reading
`instanceConfig.get(ACFG.…)` — and a frontend that reads it back from a config endpoint — sees them. Rule of
thumb: **env vars for the documented ops levers** (`KDR_PORT`, `KDR_IN_MEMORY_ONLY`, `KDR_OBFUSCATE_ERRORS`,
`KDR_ALLOW_TEST_ENDPOINTS`); **your own config object for product/UI values** that have no env var, and for a
stable personal setup that won't collide with the developer's.

## Notes

- **The project must be named `customConfig`.** That is the name the versioned
  `launch` build looks for (`findProject(":customConfig")`). To use a different
  object *name* within it, set the `KDR_CUSTOM_CONFIG` environment variable (it
  defaults to `KdrConfig`).
- **Runtime only.** `customConfig` is added to `launch`'s runtime classpath, never
  its compile classpath — the launcher reaches `KdrConfig` purely by reflection.
- **Not version-controlled.** These files live in the deployment's parent
  directory, alongside `settings.gradle.kts`, outside the `KotlinDynamicRuntime`
  repository.
