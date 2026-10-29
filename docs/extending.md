# Extending and customizing the runtime

Many applications are built from this one runtime. What a given application does is decided by which components it
loads, how its deployment is configured, and what each of its clients has defined for itself. This document maps every
way to vary the runtime today, organized by who does the varying, and says where each is described in depth.

Not all of it is an extension point in the narrow sense, an interface somebody implements. Much of it is
configuration: a client's workflow is data, stored and published, and no code is written for it. The map covers both,
because the question a reader brings is the same: *how do I make it do this?*

## The rule behind it: on-demand customization hooks

**Behavior outside the runtime's own theme is not written into the core.** When an application needs something the
core does not do, the change to the core is a place to plug the behavior in, or an option to switch it on. The
behavior itself is supplied from outside the core code base: by a component, by a deployment's own code, or by a
client's configuration.

**We do not try to anticipate every customization,** though many are anticipated already, and most of this document
lists them. When a variation turns up that the existing mechanisms cannot express, in the backend or the frontend, the
core gains a hook for that variation at that point, and the variation is injected through it. The alternative,
inlining a special case beside the behavior it varies, is what this rule exists to prevent. Each inlined case makes the
core harder to read and harder to change, and it ties every deployment to one deployment's needs.

So the list below is not a fixed boundary. It is what has been needed so far. A need it does not cover is a reason to
add a hook, not a reason to fork the code.

## Three layers, and how a definition matures

Three kinds of people vary the runtime, at three speeds:

| Who | Varies it with | A change takes effect |
|---|---|---|
| **A deployment's operator** | environment variables, flavors, the deployment's own configuration code, and the modules it adds to the build | when the node is restarted, or rebuilt and redeployed |
| **A component author** | Kotlin code, in a component the runtime discovers at startup | when the node is rebuilt and redeployed |
| **A client's administrator** | stored configuration, written through the API or the webapp | at a reload of that client, with no restart, and as a draft first where the client has a sandbox |

The layers are built from the same objects. A trait, a workflow, a report, or a piece of copy declared in a
component's code is the same definition as one a client stores, identified the same way. That is what lets a
definition **mature**: it starts as a client's stored configuration, quick to change while the client is still finding
out what it needs, and when the client's application settles, it moves into source code as a component that is
reviewed, tested, and released. See *Where config comes from, and where it goes* in
[`design/gedra/config-and-data.md`](design/gedra/config-and-data.md).

## The deployment

| What | How | In depth |
|---|---|---|
| **Settings** | Environment variables, each declared once in code. A running node lists them all, with their resolved values, at `/operator/env/reference`. | [README, Configuration](../README.md#configuration) |
| **Configuration in code** | An `AppConfigApplier`, discovered at startup and chosen by `KDR_CUSTOM_CONFIG`, sets instance configuration that no environment variable reaches. `kdr-create-config` scaffolds one. | [`examples/custom-config.md`](../examples/custom-config.md) |
| **Its own modules and components** | The deployment's `settings.gradle.kts`, which is not version-controlled, adds modules to the build. A module's components are then discovered the same way as the runtime's own. | [`examples/settings.gradle.kts.example`](../examples/settings.gradle.kts.example) |
| **Named variants** | A deployment flavor (`KDR_DEPLOY_FLAVORS`) switches on a set of settings that components declared together, such as a reference instance. | the `kdr-deploy-flavors` skill |
| **Branding** | The webapp's favicon and logo for the whole deployment, shipped as files beside its configuration code, without a fork of the webapp. | [`examples/custom-config.md`](../examples/custom-config.md#branding-the-webapp) |
| **Files** | Where uploaded files are kept (memory, a directory, or S3), and which directories on the node hold images clients may name. | [`design/file-refs.md`](design/file-refs.md), [`deploy/lightweight-node.md`](deploy/lightweight-node.md) |

**How discovery works.** Anything a deployment injects implements `KdrProvider` and names itself in a
`META-INF/services` file, and the JVM's `ServiceLoader` finds it at startup. Nothing scans the classpath, so only a
class written for this interface is ever loaded, and what is on the classpath is decided entirely by the deployment's
settings file. Injection is therefore explicit and auditable. A component and a configuration applier are the two kinds
of provider today.

## A component

A component is the unit the application is assembled from. The runtime's own features are components, and a
deployment's are written the same way. A component implements `ComponentDefinition`, and every method of that
interface is a place it can contribute; each has a default that contributes nothing.

| A component can contribute | Through | In depth |
|---|---|---|
| **Where it loads** | `presence`: the boot roles and capability tags of the nodes that carry it. `isLoaded` narrows that further, for decisions that cannot be declared. | `ComponentDefinition` |
| **Settings it owns** | `applyInstanceConfig`: defaults for configuration the component reads, applied before anything reads them. A deployment's own choice always wins. | `ComponentDefinition` |
| **Flavors** | `flavorDefs` declares a flavor, and `flavors` contributes settings to one, its own or another component's. | the `kdr-deploy-flavors` skill |
| **Endpoints and their types** | `addSchema`, with a schema module: the endpoint builders and the `Sch*` schema DSL. | the `kdr-endpoint-builder` and `kdr-schema-builder` skills |
| **Tables, and caches over them** | `addSchema`, through the collector. | the `kdr-table-cache` skill |
| **Traits, workflows, clients, reports, and cfacts** | `gedraConfigs`, with the `gedraConfig { ... }` DSL. These are the same definitions a client stores. | the `kdr-test-clients` skill, [`design/client-definition.md`](design/client-definition.md) |
| **Copy** | `fragments`: Markdown fragment files, and overlays on another component's files. | the `kdr-webapp-pages` skill (`static-content.md`) |
| **Presentation structure** | `uiBlocks`: blocks such as the menu, and overlays on another component's blocks. | [`design/ui-block.md`](design/ui-block.md) |
| **Documents** | `docs`: Markdown pages served in-app and offered on the Documents page. | `ComponentDefinition` |
| **Services** | `startupServices` and `services`. | `ComponentDefinition` |

**Naming keeps components apart.** A component's global names live under an owner root of its own, such as `kdr` for
the runtime's components. A component may extend another owner's names one contribution at a time by saying so
(`contributesTo`). What the boot refuses is the accident: a name under no root, or under the reserved `client` root.
See *Visibility* and *What is checked today* in [`design/gedra/config-and-data.md`](design/gedra/config-and-data.md).

**Behavior attached to definitions.** A definition is data and a computation is Kotlin, so the two are kept apart: a
trait or workflow names a function, and a component registers the function with the schema collector during
`addSchema`.

| Hook | What it does | Registered with |
|---|---|---|
| **Options provider** | Supplies the choices a field offers at run time (`g-optionsSource`). Stored configuration may name only a provider registered as allowed to be named. | `addOptionsProvider` |
| **Data deriver** | Computes a trait's derived values when a form is read, for display. Nothing is stored. | `addDataDeriver` |
| **State deriver** | Computes a form's derived state from its data, in the same transaction as each write. | `addStateDeriver` |
| **Prep-for-save function** | Adjusts a trait's data before it is saved. | `addPrepForSaveFn` |
| **Write guard** | Checks a write to an existing form under the form's lock, and may refuse it. Workflow locks are built on this. | `addWriteGuard` |
| **Write hook** | Acts after every create, patch, or import, inside the write's transaction. | `addWriteHook` |
| **Workflow function** | The Kotlin behind a function a workflow definition names, with its own initialization data. | `addWorkflowFunction` |
| **Cfact** | A named fact about the caller or the client, which expressions in definitions test. | `addCFact` |
| **Job** | Scheduled work, divided by client and then into tasks. | `addJob` |
| **File bucket** | A named place in the file store. | `addFileBucket` |

Each hook's contract is in its KDoc. A guide to writing a component, covering these hooks and how a component is
shipped as a deployment's own module, has not been written yet.

**Simulations.** A component may also provide a simulation, which provisions a client with data for demonstration or
testing on a test instance (`simulationEndpoint`). The conference demo is provisioned this way.

## A client

A client is one tenant of a deployment: its own users, data, and definitions, kept apart from every other client's.
Everything in this section is **configuration**, stored in the database and written through the API, mostly through
the webapp. No code is involved, and a change is made live by reloading the client, not by restarting the node.

| A client can define | In depth |
|---|---|
| **Traits**: the named pieces of data its forms collect, and which global traits it uses | [`design/client-definition.md`](design/client-definition.md), *The traits a client supports* |
| **Schema**: its own types, and alterations of global types for itself. A type can extend another (`g-extends`), narrow it, or give it a field layout. | the `kdr-schema-builder` skill |
| **Workflows**: entries, tasks, eligibility, approvals, and locks | the [conference demo walkthrough](demo-client/README.md) |
| **Cfacts**: the facts its eligibility and display rules test | the [conference demo walkthrough](demo-client/README.md) |
| **Reports**: named reports over its forms, with history | [`design/reporting.md`](design/reporting.md) |
| **Copy**: replacing any text the application shows, key by key | the webapp's copy editor |
| **The menu**: renaming, hiding, or showing an item to a named audience | [`design/ui-block.md`](design/ui-block.md) |
| **Images**: named images its copy shows (file refs), and its favicon and brand mark (web assets) | [`design/file-refs.md`](design/file-refs.md) |
| **Its definition**: name, labels for its users, personas, and time zone | [`design/client-definition.md`](design/client-definition.md) |

**Starting from another client.** A client can be built on a template (`extendsFromClientId`): the template's
definitions become the client's own, and the client replaces whichever of them it redefines. A workflow is replaced
whole. Adding tasks to another owner's workflow without replacing it is not supported, and would be added as a hook
when a client first needs it. See *Inheritance between clients* in
[`design/client-definition.md`](design/client-definition.md).

**Trying a change first.** A client with a sandbox runs only what it has published. Its edits are drafts, which its
sandbox runs against users and data of its own. A publish is checked against the client's stored forms before it
reaches the client's people. See [`design/sandboxes.md`](design/sandboxes.md).

**A mistake costs little.** Stored configuration is held strictly to its rules when it is written, and read back
forgivingly: a fault in a client's stored configuration costs only the faulty piece, never the node. See *What is
checked, and what a failure does* in [`design/gedra/config-and-data.md`](design/gedra/config-and-data.md).

## What is not extensible today

Some parts of the runtime are deliberately closed, and a variation there needs a change to the core:

- **The webapp's pages and widgets.** Pages are chosen in the webapp's own code. A component contributes the data a
  page shows, such as copy, menu items, endpoints, and definitions, but not a new page.
- **Frontend actions.** Server-driven UI invokes frontend behavior only by naming an entry in a fixed registry, kept in
  shared kernel code so that a reference is checked at boot. See [`design/ui-block.md`](design/ui-block.md).
- **File store providers.** Memory, a directory, and S3 are the choices. There is no interface for another.

These are not boundaries in principle. Under the rule at the top of this document, the first need for a variation in
one of them is the point at which it gains a hook.
