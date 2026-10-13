# Example: a deployment-supplied custom configuration module

A deployment can inject its own configuration code, which the launcher discovers at startup through the JVM's
standard **ServiceLoader** mechanism (issue #171) and applies to the application config. This is the mechanism
behind the code-guide's "a deployment agent can add Kotlin code specific to the deployment and have it picked up
by the build."

This file is a guide, not live code — copy the snippets into real files in your (non-versioned) deployment
directory.

## How it works

- `config` defines `interface AppConfigApplier : KdrProvider { fun AppConfigBuilder.applyAppConfig() }`. The
  method's receiver is the builder, so the body reads as a Kotlin builder DSL.
- Your config class is a **provider**: it implements `AppConfigApplier` and registers itself in a
  `META-INF/services/com.dynamicruntime.common.startup.KdrProvider` file (the shared base type all providers are
  discovered through). At startup the launcher enumerates providers with `ServiceLoader.load(KdrProvider)`,
  **selects** the config applier whose `providerName` (its simple class name) matches the `KDR_CUSTOM_CONFIG`
  environment variable — defaulting to `KdrConfig` — and invokes it. It logs every provider it discovers, by
  name and source jar, so what got loaded is always visible in the startup log.
- The deployment provides a Gradle project (conventionally **`:customConfig`**) living outside the
  version-controlled source tree, and injects it via one `injectComponent(...)` line in `settings.gradle.kts`.
  That both co-builds the project and adds it to `launch`'s **runtime** classpath (never compile — the launcher
  reaches your class purely through `ServiceLoader` and the `AppConfigApplier` interface). Its absence is not an
  error.

## Add a custom configuration — step by step

> **Shortcut:** the `kdr-create-config` command scaffolds everything below for you — the project, the placeholder
> `KdrConfig`, the `META-INF/services` file, and the `settings.gradle.kts` wiring — non-destructively (it never
> overwrites an existing file, and a commented-out `injectComponent` counts as present). Run it after
> `kdr-install`; then edit the generated `KdrConfig`. The manual steps below explain what it produces.

In the directory that *contains* `KotlinDynamicRuntime/` (the same directory as your `settings.gradle.kts`):

1. **Create the project**, with `apps` as the Kotlin source root and `resources` enabled (the service file lives
   there):

   ```
   customConfig/
   ├── build.gradle.kts
   ├── apps/
   │   └── com/dynamicruntime/deploy/
   │       └── KdrConfig.kt
   └── resources/
       └── META-INF/
           └── services/
               └── com.dynamicruntime.common.startup.KdrProvider
   ```

2. **`customConfig/build.gradle.kts`** — mirrors the `launch` technique (the `apps` subdirectory is the Kotlin
   source root directly, so the build script and output stay outside it). It needs only `:config`, which
   re-exports the base modules (`common`/`kdn`) via `api`. Resources are enabled so the project can ship its
   service file:

   ```kotlin
   plugins {
       id("kdr.kotlin-conventions")
   }

   sourceSets {
       main {
           kotlin.setSrcDirs(listOf("apps"))
           resources.setSrcDirs(listOf("resources"))
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

3. **`customConfig/apps/com/dynamicruntime/deploy/KdrConfig.kt`** — a **`class`** (not an `object`: ServiceLoader
   instantiates via a public no-arg constructor, which a Kotlin `object` does not have) in the reserved
   `com.dynamicruntime.deploy` package. Implement `AppConfigApplier`; inside the override, the
   `AppConfigBuilder` is the implicit receiver:

   ```kotlin
   package com.dynamicruntime.deploy

   import com.dynamicruntime.common.context.ENV
   import com.dynamicruntime.config.AppConfigApplier
   import com.dynamicruntime.config.AppConfigBuilder

   class KdrConfig : AppConfigApplier {
       override fun AppConfigBuilder.applyAppConfig() {
           env = ENV.prod
           inMemoryOnly = false
           data["featureX"] = true
       }
   }
   ```

4. **`customConfig/resources/META-INF/services/com.dynamicruntime.common.startup.KdrProvider`** — register the
   class for discovery. The file name is the fully qualified name of the `KdrProvider` base interface (the dots
   are literal — it is one flat file, not nested directories); its contents are the fully qualified names of
   your provider classes, one per line:

   ```
   com.dynamicruntime.deploy.KdrConfig
   ```

5. **Inject it in `settings.gradle.kts`** (see the prologue in `examples/settings.gradle.kts.example`):

   ```kotlin
   injectComponent(":customConfig", "customConfig")
   ```

   That single line is all the deployment adds — it co-builds the project and wires it onto `launch`'s runtime
   classpath. No edits to `launch` or any version-controlled build file are needed.

6. **Select which applier runs** (when more than one is present) with `KDR_CUSTOM_CONFIG`, matched against the
   provider's simple class name; unset defaults to `KdrConfig`:

   ```bash
   KDR_CUSTOM_CONFIG=KdrConfig ./gradlew :launch:run
   ```

## Branding the webapp

A deployment can serve its own artwork — favicon, logo — without forking `:webapp`. Name a classpath directory
with the `appUiBrandingDir` config key and ship the files there:

```kotlin
class KdrConfig : AppConfigApplier {
    override fun AppConfigBuilder.applyAppConfig() {
        data["appUiBrandingDir"] = "acmeBranding"
    }
}
```

`customConfig` is on the runtime classpath and (per step 2) already has resources enabled, so it is the natural
carrier. Place the files under the `resources` root, in a directory matching the key (alongside the
`META-INF/services` file):

```
customConfig/
├── apps/com/dynamicruntime/deploy/KdrConfig.kt
└── resources/
    ├── META-INF/services/com.dynamicruntime.common.startup.KdrProvider
    └── acmeBranding/
        ├── favicon.svg          # tab icon (heavier strokes, for 16px)
        ├── brand-mark.svg       # the app bar logo and home hero
        ├── favicon-32.png       # tab-icon fallback
        ├── apple-touch-icon.png # 180×180, opaque — iOS home screen
        └── favicon.ico          # legacy
```

Notes:

- **Every file is optional.** Resolution is per asset, so you can override just `brand-mark.svg` and inherit the
  rest. Anything you omit falls back to the built-in.
- **You override a filename, not the shell markup.** The shell links each asset by its stable name
  (`favicon.svg`), but since issue #137 the served URL has carried a content hash of the *served* bytes
  (`/wa/favicon.svg:<hash>`). So branding an asset gives it its own hashed URL automatically — you never touch
  the `<link>` tags, and immutable caching can never serve a stale built-in in a branded asset's place.
- **Watch the startup log.** A directory that overrides nothing logs a warning: the usual cause is a typo, or
  resources not reaching the classpath, and without the warning it just looks like the branding "didn't take"
  (the app serves its built-in set and appears fine).
- **Production only.** The webpack dev server serves `:webapp`'s own resources, so it always shows the built-in
  set. Only the deployed app (`appui`) is branded.
- **The stylesheet is not brandable.** Replacing `app.css` wholesale would fork it and re-create the drift a
  single sheet exists to prevent; theming wants CSS variables instead.
- **This is the deployment's branding, not a client's.** A client's administrator uploads a favicon and a brand
  mark of its own from the Clients page (issue #1130), with no code and no redeploy; those stand in for these for
  that client's signed-in people. The set here is what everybody else sees, and what every page paints first.

## Local files a file ref may name

A client's **file ref** (issue #1153) is a named image its configuration holds. Most are uploaded, and live in the
file store. But some images are already on the machine -- a source repository's own artwork, the pictures a demo
client is provisioned with -- and a ref can name one where it is, with **no import** and no second copy to keep in
step (issue #1154). The configuration names a **root** and a path under it; the deployment says where roots are.

```kotlin
gedraConfig(cxt, "main", "client.acme", "acme") {
    fileRef("logo", "The logo acme ships with.") { local("brand", "logo.svg") }
}
```

That ref is served as any other is, at `/st/<appId>/ref/logo:<hash>`, the hash being that of the file as it is
read. Edit the file and its address changes; nothing needs reloading.

**Where local files are** is `KDR_LOCAL_FILES_DIR`, which defaults to `localFiles` in the workspace directory,
beside `fileStore` and `h2Database`. Each directory directly under it is a root, by its name:

```
localFiles/
└── brand/            # the root "brand"
    ├── logo.svg      # local("brand", "logo.svg")
    └── marks/
        └── crest.png # local("brand", "marks/crest.png")
```

Nothing creates `localFiles` or a root. **Until you make one there are no roots and no local ref resolves** --
which is the safe default for a deployment that does nothing. The location is resolved as the file store's
directory is: a relative path is taken from the workspace directory, an absolute one as written, so the images
need not be in the workspace at all (a mounted volume, a directory another process maintains).

**To make a source repository's artwork a root without copying it**, place the root there by name:

```
KDR_LOCAL_FILE_ROOTS=brand=acmeRepo/images,shared=/srv/kdr/imagery
```

Each location is resolved by the same rule, and a root declared this way takes the place of a directory of the
same name under `KDR_LOCAL_FILES_DIR`. Deployment is a git pull and a build at launch, so a root inside a source
repository is on every node by the pull; **a location outside the workspace is yours to put on every node**.
Nodes that hold different files give a ref different addresses, and each answers the other's with `no-store`.

Notes:

- **What may not be a root.** Neither the location nor a root may be inside one of the places that hold the
  deployment's own data -- the workspace's `private/`, `h2Database/`, `logs/` and `sampleFiles/`, and the file
  store's directory -- nor be a directory that *contains* one, which rules out the workspace directory itself, a
  home directory and `/`. This is judged by real path, so a link into such a place is in it. A relative
  declaration holds no `..` and no name beginning with a dot. **A declaration that breaks any of this refuses the
  boot, in every environment**: it is a fence, not a preference.
- **A root's name** is letters, digits, `_` and `-`. A directory under the location with any other name is not a
  root.
- **A path is plain**: segments of letters, digits, `.`, `_` and `-`, none beginning with a dot. A file reached
  through a symbolic link below the root is not served, nor one whose name differs from the path only by case
  (which would resolve on a Mac and not on Linux). A root may itself be a link.
- **Images only, up to 2 MB**: PNG, SVG, ICO, JPEG or WebP, told by the bytes and not by the file's name. A file
  that is anything else is never served, so a local ref cannot be made to disclose anything but a picture.
- **A ref whose file cannot be served is a configuration problem** when its client loads -- a refused boot in
  development and in tests, a warning in production, where the ref is answered as not found until the file is
  there. The problem says one thing whatever the reason; the reason is in the node's log.
- **Who may write one.** Source configuration, always. In *stored* configuration, the server's own code (a
  script or a simulation provisioning a client) and an administrator who sees every client. A client's own
  administrator may not name a local file -- they upload one, which overrides a shipped ref of the same id, and
  removing the upload reveals the shipped image again.
- **A unit test never reads the workspace.** In the unit environment the location has no default, and a relative
  location or root is refused: a test names `KDR_LOCAL_FILES_DIR` as a temporary directory through its instance
  config (`LocalFileRefsTest.kt`).

## Tuning batch jobs

A deployment tunes its batch jobs through their **profiles** -- threads, heartbeat, lease, retries, synchronous
caps, trace level -- and the node's **scheduler**, with typed settings (issue #870):

```kotlin
class KdrConfig : AppConfigApplier {
    override fun AppConfigBuilder.applyAppConfig() {
        jobProfile("workflowStates") {
            leaseTimeoutMs = 60_000
            trace = JobTraceLevel.launch
        }
        jobScheduler { tickMs = 30_000 }
    }
}
```

Settings are layered, lowest first: the job's registered profile in code; a component's contributions, which
fill only what is still unset; this config object; and, for the trace level, the launch itself. So what you set
here always stands over a component's defaults. A setting for a profile no registered job uses, or a setting
that does not exist, fails the boot's `jobConfig` check (`KDR_JOB_CONFIG_CHECK` sets its mode), rather than being
silently ignored.

## For Claude: controlling your own config while testing (issue #152)

*This section is addressed to Claude (the coding agent), not to a human deployer.* The rest of this file is
about a deployment shipping production config; here the same mechanism is a **test harness** — a way for you to
set instance config on a server you boot to verify a change.

The reason to use it: when a behavior is gated on a config value that has **no environment variable** — a UI
tuning value like an idle-refresh interval, or any `AppConfigBuilder` property — you would otherwise have no
way to set it for a live run. `KDR_CUSTOM_CONFIG` lets you **select** a discovered config provider *by its
`providerName`*, so you provide your **own** class rather than editing the developer's `KdrConfig`. That
isolation is the whole point: a value the developer sets for their own run can't break yours, your edits can't
disturb theirs, and neither of you touches the other's file. (This is the same collision that once broke a live
test and motivated the split.)

**First, the one-time setup — the `customConfig` project may not exist in your workspace yet.** It, the
`injectComponent` line that wires it, and the provider files are non-versioned, so every workspace has its own;
a fresh one (Eva's, say) has neither the project nor a `KdrConfig`. Two cases:

- **No `customConfig` project yet** (no `customConfig/` beside your `settings.gradle.kts`, or no
  `injectComponent(":customConfig", ...)` in it): create it first, exactly as **"Add a custom configuration"**
  above describes — but you only need your own `ClaudeConfig.kt` in `apps/com/dynamicruntime/deploy/`, listed in
  the service file. Do **not** add a `KdrConfig`; that class is the developer's to create, not yours.
- **It already exists** (the developer uses `KdrConfig`): add `ClaudeConfig.kt` beside theirs in the same
  package, and **append** its FQN to the existing `META-INF/services/...KdrProvider` file (leave `KdrConfig`'s
  line and file alone).

Either way the files are non-versioned, so they never land in a PR. The service file then lists both:

```
com.dynamicruntime.deploy.KdrConfig
com.dynamicruntime.deploy.ClaudeConfig
```

```kotlin
// customConfig/apps/com/dynamicruntime/deploy/ClaudeConfig.kt (providerName is the simple name "ClaudeConfig")
package com.dynamicruntime.deploy

import com.dynamicruntime.config.AppConfigApplier
import com.dynamicruntime.config.AppConfigBuilder

@Suppress("unused")
class ClaudeConfig : AppConfigApplier {
    override fun AppConfigBuilder.applyAppConfig() {
        // Any AppConfigBuilder property (env, inMemoryOnly, validateResponseSchema, idleBumpIntervalMs, …).
        idleBumpIntervalMs = 3000            // e.g., a 3s idle bump, to observe it without the one-minute wait
        // A key without a typed property yet: set it straight into the config map.
        data["someFutureKey"] = true
    }
}
```

Select it when you boot your server (see the `kdr-testing` skill for the port/in-memory conventions):

```bash
cd "$KDR_WORKSPACE_DIR" && \
  KDR_DEFAULTS_FILE=none KDR_PORT=7071 KDR_IN_MEMORY_ONLY=true KDR_CUSTOM_CONFIG=ClaudeConfig ./gradlew :launch:run > /tmp/srv.log 2>&1 &
```

(`$KDR_WORKSPACE_DIR` is the workspace root — set it, or resolve it by walking up to the nearest
`settings.gradle.kts`, as the `kdr-testing` skill shows. It is never a fixed path.)

The values become instance config exactly as a deployment's would, so an endpoint reading
`instanceConfig.get(ACFG.…)` — and a frontend that reads it back from a config endpoint — sees them. Rule of
thumb: **env vars for the documented ops levers** (`KDR_PORT`, `KDR_IN_MEMORY_ONLY`, `KDR_OBFUSCATE_ERRORS`,
`KDR_TEST_INSTANCE`); **your own config class for product/UI values** that have no env var, and for a stable
personal setup that won't collide with the developer's.

## Notes

- **Discovered, not named.** The versioned build names no provider. `injectComponent(":customConfig", …)` in
  your settings both co-builds the project and records it; `launch`'s `wireInjectedComponents()` adds every such
  project to the runtime classpath, and `ServiceLoader` discovers the providers there. To pick among several
  appliers, set `KDR_CUSTOM_CONFIG` to the target's `providerName` (its simple class name; defaults to
  `KdrConfig`). `providerName` is assumed globally unique.
- **`class`, not `object`.** ServiceLoader instantiates providers via a public no-arg constructor.
- **Package.** Deployment config lives in the reserved `com.dynamicruntime.deploy` package (the framework ships
  no code there). Full-blown **components** (schema + services), by contrast, use their own vendor/reverse-DNS
  package and a normal `src/main/kotlin` layout — they are a separate, later kind of provider.
- **Runtime only.** Injected projects are added to `launch`'s runtime classpath, never its compile classpath —
  the launcher reaches your class purely through `ServiceLoader` and the `AppConfigApplier` interface.
- **Not version-controlled.** These files live in the deployment's parent directory, alongside
  `settings.gradle.kts`, outside the `KotlinDynamicRuntime` repository.
