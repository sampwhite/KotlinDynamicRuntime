# IntelliJ dev setup: running and debugging locally

This describes the IntelliJ run configurations for running the server and the
`webapp` front end, and for debugging both at once. Like `settings.gradle.kts`
and `gradle.properties` (see [`settings.gradle.kts.example`](settings.gradle.kts.example)
and [`gradle.properties.example`](gradle.properties.example)), these configs are
a **deployment-local** artifact: they live in the directory that *contains*
`KotlinDynamicRuntime/` — the Gradle build root and the IntelliJ project root —
under `.idea/runConfigurations/`, and are **not** version-controlled. This file
is the checked-in instructions for recreating them.

> Why not commit the config files? IntelliJ reads run configurations from the
> project base dir (the parent), and `$PROJECT_DIR$` resolves there too — not to
> this repo. Given the deliberate parent-root layout, the config files can only
> live in the (non-versioned) parent, so the repo carries the recipe instead.

## Prerequisites

- **`gradle.properties` at the build root** with a raised heap, or the Kotlin/JS
  webpack tasks OOM. Copy [`gradle.properties.example`](gradle.properties.example)
  to the parent directory as `gradle.properties`.
- **IntelliJ IDEA Ultimate** (or the JavaScript/TypeScript plugin) for the
  JavaScript Debug configuration used to debug the front end.
- After changing the `webapp` npm dependency set, regenerate the Kotlin/JS
  lockfile once with `./gradlew kotlinUpgradeYarnLock` (the lockfile lives at
  the build root under `kotlin-js-store/`).

## How the pieces fit

- The **server** boots the runtime and serves HTTP on **:7070** under the `kda`
  context root. Use either `:launch:run` (the main app; it registers the demo
  Todo endpoints in developer environments) or `:sample:run` (the standalone
  demo). Both expose `/kda/todo/*`.
- The **webapp** dev server runs on **:8080** and proxies `/kda` → `:7070`, so
  the browser makes same-origin calls (no CORS). Start the server first, or the
  Todo list shows an "API call failed" error.

## Run configurations to create

Create these under **Run → Edit Configurations → +**. IntelliJ stores each as an
XML file under the parent's `.idea/runConfigurations/`. For the Gradle ones, set
the **Gradle project** to the build root and the **Run** field to the task shown.

| Name | Type | Task / setting |
| --- | --- | --- |
| `KDR server (:launch:run)` | Gradle | `:launch:run` |
| `sample API (:sample:run)` | Gradle | `:sample:run` |
| `webapp` | Gradle | `:webapp:jsBrowserDevelopmentRun` |
| `webapp (no browser, for debugging)` | Gradle | `:webapp:jsBrowserDevelopmentRun`, arguments `-Pwebapp.open=false` |
| `webapp debug (Chrome @8080)` | JavaScript Debug | URL `http://localhost:8080`, browser Chrome |
| `Todo app (server + webapp)` | Compound | runs `KDR server (:launch:run)` + `webapp` |

The Gradle and Compound configs are plain XML. For example, `KDR server`:

```xml
<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="KDR server (:launch:run)" type="GradleRunConfiguration" factoryName="Gradle">
    <ExternalSystemSettings>
      <option name="externalProjectPath" value="$PROJECT_DIR$" />
      <option name="externalSystemIdString" value="GRADLE" />
      <option name="taskNames">
        <list>
          <option value=":launch:run" />
        </list>
      </option>
    </ExternalSystemSettings>
    <method v="2" />
  </configuration>
</component>
```

The `webapp (no browser, for debugging)` config is the same as `webapp` but with
`-Pwebapp.open=false` in `<option name="scriptParameters" value="-Pwebapp.open=false" />`.

The compound:

```xml
<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="Todo app (server + webapp)" type="CompoundRunConfigurationType">
    <toRun name="KDR server (:launch:run)" type="GradleRunConfiguration" />
    <toRun name="webapp (:webapp:jsBrowserDevelopmentRun)" type="GradleRunConfiguration" />
    <method v="2" />
  </configuration>
</component>
```

The `webapp debug (Chrome @8080)` config is a **JavaScript Debug** type
(create it through the UI; its XML varies by IDE version). Set the URL to
`http://localhost:8080` and the browser to Chrome.

## Everyday run (no debugging)

Run **`Todo app (server + webapp)`** with the green **Run** button. It starts the
server on :7070 and the webapp on :8080, and the dev server opens **Chrome** at
http://localhost:8080 (the webpack devServer `open` targets Chrome specifically).

## Debugging both the server and the webapp

The server (JVM/Kotlin) and the webapp (JS in the browser) are debugged by two
separate debuggers, run at the same time:

1. **Server — JVM debug.** Launch **`KDR server (:launch:run)`** with **Debug**.
   Breakpoints in endpoint handlers (e.g. `TodoService`, the request pipeline)
   bind and hit. Debugging a Gradle `run` this way is expected and fine.

2. **Webapp — JS debug.** Two steps, in order:
   - Start **`webapp (no browser, for debugging)`** with **Run**. It compiles the
     (large, source-mapped) dev bundle and serves :8080 **without** opening a
     browser. The task is a long-running server — it stays running.
   - Then launch **`webapp debug (Chrome @8080)`** with **Debug**. It opens Chrome
     at :8080 with the debugger attached; breakpoints in `.kt` files bind via the
     dev build's source maps.

You then get the full loop: JS breakpoints in the browser → `fetch('/kda/todo/…')`
→ proxied to :7070 → JVM breakpoints in the endpoint handlers.

### Why "no browser" for the webapp while debugging

IntelliJ's JS debugger has to launch Chrome itself with a remote-debugging port,
and Chrome allows only one process per profile. If the dev server has already
opened Chrome (its default `open` behavior), the debugger's launch collides and
IntelliJ shows **"Another browser process is already running."** The
`-Pwebapp.open=false` switch makes the dev server not open a browser, leaving the
debugger as the sole opener. (For a normal run you want the auto-open, so it stays
on by default.)

## Troubleshooting

- **"Another browser process is already running" / a Chrome window opens at a
  random port (e.g. :56289):** you launched the *Gradle* `jsBrowserDevelopmentRun`
  task with **Debug**, which triggers IntelliJ's own Kotlin/JS browser launch at a
  guessed port. Use **Run** for the dev server and debug via the JavaScript Debug
  config instead (above). Close any leftover debug-Chrome window before retrying.
- **`java.lang.OutOfMemoryError` during the webapp build:** the build root has no
  `gradle.properties` with a raised heap — see Prerequisites.
- **"Lock file was changed. Run the `kotlinUpgradeYarnLock` task":** the webapp's
  npm dependencies changed; run that task once (see Prerequisites).
