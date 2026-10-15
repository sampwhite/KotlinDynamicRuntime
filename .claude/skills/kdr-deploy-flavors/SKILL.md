---
name: kdr-deploy-flavors
description: Give a KotlinDynamicRuntime deployment a named variant with deployment flavors (KDR_DEPLOY_FLAVORS) — declaring a flavor on a component (FlavorDef), contributing to one with the `flavor(name) { set(...); default(...) }` DSL, how and when flavors resolve, the precedence of a flavor's values against the deployment's own settings, the boot refusals, and the operator view. Use when a deployment needs a variant (a reference instance, a dedicated deploy), when adding a switch a flavor should set, or when booting a test with a flavor.
---

# Deployment flavors (`common/flavor`)

A **deployment flavor** is a named package of configuration changes a deployment chooses with
`KDR_DEPLOY_FLAVORS` -- a comma-separated list, such as `reference`. Issues #1217-#1221 build it up; the design
is in `kdr-design/thoughts-on-flavors.md` (private `sampwhite/Actions`).

**The rule everything else follows: a flavor sets values, and code reads the values.** Nothing tests a flavor
except the engine. A component that should behave differently under a flavor reads a config key or an
environment variable, the same way it reads any other, and the flavor sets that key or variable. So each switch
also works alone, a test sets one without the rest of the package, and the operator view can say where a value
came from. It is also what keeps flavors from interacting: a flavor is a list of changes with no logic in it.

## Declaring and contributing

The first real flavor is the sample component's **`samples`** (`SampleComponent.samplesFlavor`): it defaults
`KDR_LOAD_SAMPLE` and points the home page at the sample clients through `sampleSamples_omnibus.md`. To try a
deployment's own copy, boot with `KDR_DEPLOY_FLAVORS=samples` and a `flavor-overlays/samples.md` in the
workspace (`# !home`, `# @home`, `# +intro ...`).

One component **owns** a flavor and declares it; any component may **contribute** values to it:

```kotlin
class ReferenceComponent : ComponentDefinition {
    override fun flavorDefs(cxt: KdrCxt) = listOf(FlavorDef("reference", "A public reference deployment."))

    override fun flavors(cxt: KdrCxt) = listOf(
        flavor("reference") {
            set("confDemoDocLinks", true)        // an instance-config key
            default(sampleLoadEnvVar, "true")    // an EXISTING environment variable
            omnibusOverlay("kdrReference")       // md-fragments/kdrReference_omnibus.md
        },
    )
}
```

- **`set(key, value)`** sets an instance-config key. This is how a **new** switch is made: never add an
  environment variable for a flavor to set, since keeping their number down is what flavors are for.
- **`default(def, value)`** defaults an environment variable that **already exists**, for a setting configured
  through one today. Name it by its `EnvVarDef`, as every read does.
- Set an existing setting through its environment variable, never through a config key that falls back to it:
  the key would beat a variable the deployment set on purpose.
- **`omnibusOverlay(id)`** overlays the fragment files an omnibus file names (issue #1219), and
  **`fragments { ... }`** any other overlay layers (a `fragmentInline`, say). Copy is what a flavor changes most.
  The layers are made only for an **active** flavor, so an inactive flavor's files are never read, and only for a
  component that loaded. Each must be an overlay for every client.

## Where a flavor's copy ranks

A flavor's fragment overlays rank **above the components' own overlays and below every client layer** (inherited,
source, and stored): a deployment varies what components ship, and a client that reworded its copy keeps its
wording. Among flavors, the later in `KDR_DEPLOY_FLAVORS` wins; within one, component order. The rule is
`overlayPrecedence` plus each layer's `flavorRank`, not the order layers happened to register in.

**A deployment's own copy** goes in the **flavor directory** (issue #1220): `KDR_FLAVOR_DIR`, by default
`flavor-overlays/` under the workspace (a relative path resolves there; on a server, point it anywhere, such as
`/etc/kdr/flavor-overlays`). It holds one `<flavor>.md` per flavor, in the omnibus format, ranked just above every
component's layers for that flavor -- so below a later flavor's -- key by key: it names only what the deployment
changes, and its name matches no component's file. A missing directory or file is normal. The startup log names
each file read; one that will not parse is left out and judged as a fragment fault (refused outside production,
reported in it). In ranks, that is two steps per flavor (`flavorLayerRank`): the components', then the deployment's.

`EachFlavorBootsTest` (sample module) boots once with each declared flavor on its own, so a flavor overlay naming
a key its base dropped fails the build. It enumerates the declarations: a new flavor is covered with no change.

## When and in what order

Flavors resolve **once**, in `InstanceRegistry.getOrCreateInstanceConfig`: after the deployment's own
configuration is in place, and before the derived config, the node's profile, and every component's
`presence`/`isLoaded`. So a flavor's value may decide what loads. Every component is asked for its
declarations and contributions, loaded or not. Answer from nothing that is not yet in place.

Precedence:

- **A config key** a flavor sets goes in only where it is not already set. The deployment's explicit
  configuration (its `AppConfigApplier`, or a test's boot overlay) wins, then the flavor, then a component's
  `applyInstanceConfig` defaults (which read before writing), then the reader's built-in default.
- **An environment variable** a flavor defaults sits in a **flavor layer** below everything:
  instance-config entries (the workspace defaults file among them), then the process environment, then the
  flavor (`EVSRC.flavor`), then the variable's documented default. It is a layer and not a config entry because a
  config entry beats the process environment.
- **Between flavors**, the later in `KDR_DEPLOY_FLAVORS` wins. Within one flavor two contributions may not set
  the same value at all.

## Refusals

The boot is refused for:
- a name in `KDR_DEPLOY_FLAVORS` that no component declares (`none` alone means no flavors; beside others it is
  refused, as is a name listed twice);
- a flavor declared twice;
- a contribution to an undeclared flavor;
- two contributions to one flavor setting the same key or variable;
- a default for an `EnvVarDef` marked `bootstrap` -- one read before flavors resolve (`KDR_ENV`,
  `KDR_WORKSPACE_DIR`, `KDR_DEFAULTS_FILE`, `KDR_CUSTOM_CONFIG`, the log levels, `KDR_DEPLOY_FLAVORS` itself).
  Mark any new variable read at pre-boot the same way.

All but the first are checked over every contribution, active or not, so a mistake in a flavor this node is not
running still fails.

## Seeing what a flavor did

- The startup log names the active flavors.
- `GET /operator/flavors` lists the declared flavors, the active ones, and each value an active flavor set, with
  whether it took effect and, if not, what decided instead.
- `/operator/env/reference` shows a variable a flavor defaulted as coming from a deployment flavor.
- In code, `DeployFlavors.get(cxt)` is the same report. It is for diagnostics: code reads values, not flavors.

## In a test

Name the flavors in the boot overlay, as any variable:

```kotlin
val cxt = Startup.mkTestBootCxt(
    "myFlavorTest", "myFlavorTest", mapOf(FLV.deployFlavorsEnvVar.name to "reference"),
    additionalComponents = listOf(ReferenceComponent()),
)
```

Test the change a flavor makes through the value it sets, not through the flavor. `DeployFlavorsTest` (resolution,
called directly on a hand-built config) and `DeployFlavorsBootTest` (a value deciding what loads) cover the engine.
