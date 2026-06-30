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
