---
name: kdr-test-clients
description: Set up an isolated test scenario by creating a NEW CLIENT (with its own traits, workflows, schema) on a SHARED instance, instead of booting a new instance per test. Because form docs, workflows, and users are isolated by a client boundary, a fresh client id is a clean namespace, so one booted node serves many scenarios. Covers the stored-config capability this rests on — the `gedraConfig` DSL (`defineClient`/`trait`/`workflow`/`cfact`), `GedraConfigService.writeConfig`, `GedraConfigReload.reloadClient` (making config live with no restart), the `/clientAdmin/config/{bundle,bundle/write,bundle/publish,reload,publishedOnly}` HTTP endpoints, and the latest/published-only/static protection tiers (#617). Also covers placing a `TestUser` in the new client, the client-isolation boundary (client-in-id + ReadScope→SQL), and time travel (instance-wide `InstanceClock` today; per-client is a documented but unbuilt seam). Use when writing a test that needs its own client/workflow/trait/schema, when creating or editing a client/workflow/trait/schema in the database at runtime, or when deciding between a new instance and a new client for a scenario.
---

# Creating clients dynamically, and using one per test scenario

Clients, workflows, traits, and schema can be created **in the database at runtime** — they are stored config,
authored with the same DSL as the compile-time `SampleClients` / `SampleTraits`, written to a table, and made
live on a running node with no restart. The headline use is **testing**: because form docs, workflows, and users
are isolated by a **client boundary**, a new client id is a clean namespace. So a test shares one booted instance
and creates a **new client per scenario** rather than paying to boot a node each time.

## The worked example (share one instance, create a client, place a user)

Transcribed in `base/kdn/src/test/.../TestClientSkillExamplesTest.kt`, which compiles it against the live API —
so a signature change breaks the build rather than staling this. Keep the two in step.

```kotlin
// One shared instance for the whole spec -- create a client per scenario, not a node.
val cxt = Startup.mkTestBootCxt("skillTestClients", "skillTestClients")

// A sub-context in the client, with a userId so the write names who made it (optional: unset, it is the
// system user).
fun asClient(client: String): KdrCxt = cxt.mkSubContext("setup", client).also { it.userId = 9000L }

// Create a client dynamically: define it, give it a trait, persist the bundle, and reload it live.
fun createClient(client: String, trait: String) {
    val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev,
                audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
            ),
        )
        trait("${trait}Entry", trait, setOf(GedraDataType.formDoc), "The $trait trait.") {
            property("text", "A value.", required = true)
        }
    }
    GedraConfigService.get(cxt).writeConfig(asClient(client), config)   // persist the bundle
    GedraConfigReload.reloadClient(cxt, client)                         // make it live, no restart
}

createClient("scenarioA", "alphaTrait")
createClient("scenarioB", "betaTrait")

val schema = SchemaService.get(cxt)
schema.gedraTraitsFor("scenarioA").map { it.traitId } shouldContain "alphaTrait"     // its own trait
schema.gedraTraitsFor("scenarioA").map { it.traitId } shouldNotContain "betaTrait"   // and only its own
ClientService.get(cxt).present("scenarioA").shouldNotBeNull()                        // the client is present

// Place a user in the new client; its form docs / workflows / data are then isolated by that client.
val user = TestUser.create(cxt, "u@scenarioA.test", userClient = "scenarioA")
user.selfClient() shouldBe "scenarioA"
```

That reload → create-user chain is the whole technique: after `reloadClient`, the client is `present`, so
`becomeUser` accepts it (it refuses a client the node does not carry), and everything the user then creates is
confined to `scenarioA`.

## Why a new client is a clean namespace (the isolation)

Every gedra's id is `<storage>.<kind>.<client>.<baseId>` (`GedraId`), and each data row carries a read-only
`client` column. Reads are confined by `ReadScope` → SQL (`SqlScopeUtil.scopeConditions`), resolved per request
off the authenticated user's client. So a form doc, workflow-data row, or user in `scenarioA` cannot be seen
from `scenarioB`, even by an `allClients` admin on a client-scoped endpoint. That is what lets one instance hold
many scenarios without bleed — no new node, no teardown. `global` is the reserved owner of shared/app-level data;
you never author into it (`writeConfig` refuses the `global` client, and any namespace but the client's own --
core's `kdr.*` included).

**The service write does not trial; the endpoints do** (issue #843). `GedraConfigService.writeConfig` stores what
it is given (after the namespace/owner guards), so a test can store a deliberately flawed config to exercise the
forgiving load. The **endpoints** -- bundle write, patch, import, publish and the published-only toggle -- pass
`trial = true`: a trial reload of the client with the change in place refuses it (400) when it finds a problem the
client did not already have. A test writing through the endpoints therefore needs a sound config -- which is the
point.

The trial judges the change **together with the client's other stored configs**, so several bundles written to one
client must be sound as a set: two configs of one client both declaring the cfact `ready`, say, conflict, and the
second write is refused. Give each bundle its own names, or its own client.

**`audience` and `usageType` are the operator's** (issue #820). They carry authority over a client, so a client may
not set them for itself: a **client administrator's** write through `/clientAdmin` must keep them as they are, and
is refused (403) otherwise. A platform operator may set them -- the service write in the example above, an
`allClients` administrator on either surface, an import. So a scenario client created through the service can be
`internal`/`dev`; one whose definition a test rewrites as that client's own `TestUser` must keep what it was given.

## Narrowing a schema after data is captured (no restart)

Testing that a **schema change invalidates already-captured data** — a stored value a later, tighter schema
rejects — used to need a persistent H2 store and two builds (capture on the wide schema, rebuild on the narrow
one, reopen). `reloadClient` replaces the whole dance: the schema narrows **live on one running node**, so a
value valid at capture is rejected once the reload lands. Transcribed and compiled in the same
`TestClientSkillExamplesTest`:

```kotlin
val client = "narrowcap"
fun writeClient(vararg topics: String) {
    val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
        defineClient(ClientDef(clientId = client, name = client, usageType = ClientUsageType.dev,
            audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local)))
        trait("QEntry", "q", setOf(GedraDataType.formDoc), "The q trait.") {
            property("topic", "Topic") { for (t in topics) option(t) }
        }
    }
    GedraConfigService.get(cxt).writeConfig(asClient(client), config)
    GedraConfigReload.reloadClient(cxt, client)
}

writeClient("alpha", "beta")                                              // wide schema
val user = TestUser.create(cxt, "u@$client.test", userClient = client)
val entry = mapOf(GE.traitId to "q", GE.data to mapOf("topic" to "alpha"))
user.postData(GEP.formDocCreate, mapOf(GDF.entries to listOf(entry)))     // capture: accepted

writeClient("beta")                                                      // narrow: drop "alpha", reload live
user.expectError(EXC.badInput, GEP.formDocCreate, mapOf(GDF.entries to listOf(entry)))  // now rejected
```

Two revisions of the same config class (same `name`) rewrite the editable latest **in place** while it is
unpublished, so no `publish` is needed for a client on the latest tier — `reloadClient` picks up the narrowed revision.
Because there is no restart, the captured row is never wiped, so persistence is no longer the point (it was only
ever there to survive the rebuild). Prefer this to the persistent-H2 two-build pattern for any "captured data a
later schema rejects" test.

## The building blocks

- **`gedraConfig(cxt, name, namespace, client) { … }`** (`base/kernel/.../gedra/GedraConfig.kt`) builds one
  config **bundle** for one client. A client's **namespace** is `clientNamespace(client)` -- `client.<clientId>`,
  or beneath it (`client.acme.forms`) -- and nothing else is accepted (issue #949): the write refuses another,
  the load drops it, and a source config using one fails the boot outside production. Inside the block:
  - **`defineClient(ClientDef(...))`** — exactly one per bundle (a second throws). `ClientDef` needs
    `clientId`, `name`, `usageType` (`ClientUsageType`), `audience` (`ClientAudience`), and
    `enabledEnvironments` (include `ENV.unit` and `ENV.local` so a test node loads it). `staticConfig = true`
    (source only) makes it take nothing stored **in production**; elsewhere it is an ordinary client.
    - **`testFeatures = setOf(...)`** — test/demo feature names honored only on a test instance (a demo state
      derivation, a `cfactCalc` strict-unknown-cfact throw). It **round-trips through stored config (#696)**, so
      on a test instance you author a test-feature client over the API the same `writeConfig` + `reloadClient`
      way as any other — no boot-fixture component needed (`CfactCalcTest`'s strict client is the reference).
      Off a test instance the guarantee is structural, not yours to enforce: `ClientService` neutralizes a
      stored value on load (`checkClientDefs`) and an explicit `writeConfig` carrying it is **refused** — so a
      test that forces `ACFG.isTestInstance = false` cannot write one, and reads never echo one (the bundle and
      trait reads redact at `GedraConfigRow`). `TestFeaturesBoundaryTest` covers all three faces.
    - **`userLabels = listOf(...)`** — the user labels this client *suggests* (#786). A label a workflow's
      `userHasLabel { label = "…" }` names literally is checked against this list when the config loads, so a
      test that puts that function in its client's workflow must list the label here too — otherwise the
      function is dropped with "does not suggest (userLabels)", which a unit test sees as a refused
      `reloadClient`. Each entry is written once, trimmed: the `ClientDef` constructor refuses a blank, padded
      or repeated one. A *global* workflow's labels are not checked (there is no single client list).
      `UserLabelsTest` (kdn) is the reference.
  - **`trait(typeName, traitId, appliesTo, description) { <SchTypeBuilder> }`** — a trait whose data shape is
    written inline; the `{ … }` is the `kdr-schema-builder` DSL. An overload takes `dataType` (a `$ref`) instead.
  - **`workflow(workflowId, entry: WfEntry) { <WfDefBuilder> }`** — a workflow definition (or `workflowFromMap`
    for the JSON form). See the compile-time `createForm` workflow in `sample/.../SampleClients.kt` for the
    shape, and `gedra-workflow.md` for the model. An optional `label = "…"` in the block titles the page over
    the form (issue #719); like a task's label it is a template, so `%{@t("file.ns.key")}` pulls it from a
    fragment file and the boot checks the pull resolves.
  - **`cfact(name, group, description, toFrontend)`** — the cfacts the client may name in expressions.
  - **Not `stateTrait(...)`.** State is global, declared by components (issue #873): a client's config declaring
    one is refused at write, and a client-owned source config declaring one refuses the boot outside production.
  - A `schemaDef` slot exists for plain schema types too; most schema rides in via a trait's data shape.
- **`GedraConfigService.get(cxt).writeConfig(clientCxt, config)`** persists the bundle. What must be named is
  the **client** -- the config names its own, and the write binds the context to it whatever it was bound to, so
  ownership stamps from the right owner. A `userId` is optional (attribution; unset, it is the system user);
  `asClient(...)` sets one because acting as a user in the client is the usual way a test picks its client.
  Writing is revision-aware (issue #611): first write is version 1; rewriting an unpublished latest edits in place;
  writing after a publish mints the next revision.
- **`GedraConfigReload.reloadClient(cxt, client)`** is *the* thing that makes config dynamic. It withdraws the
  client's previously-loaded stored config, adds the new through the same boot checks (rolling back on any
  throw), then swaps each service's derived state — `SchemaService.reloadClient`, `ClientService.recheck`
  (which is how a *newly created* client becomes `known`/`present`), the fragment/UiBlock overlays, and
  `WorkflowService.reloadClient`. A request in flight keeps the store it started with; the next request sees the
  new one. **No restart.**
- **A sandbox reloads with its parent** (issue #928). A client whose `ClientDef` sets `sandbox = true` has a live
  sandbox client, `sandboxOf(client)` = `<client>:sandbox`, running the parent's source config and **latest**
  stored revisions whatever the parent's tier (copied under its id by `SandboxConfigs`), with users and data of its
  own. `reloadClient(parent)` reloads it too and returns its result in `.sandbox` (`.all` is both); a sandbox can
  also be reloaded on its own. A test wanting a preview client sets the flag on the parent and places a
  `TestUser` in the sandbox (`userClient = sandboxOf(parent)`) -- `SandboxClientTest.kt` is the reference.
- **Admin in a sandbox comes from the parent** (issue #929). In a sandbox, `admin` takes effect only while the
  user's identity also has an enabled admin user in the parent (or an `admin`+`allClients` one) -- so a `TestUser`
  created straight into the sandbox at `level = ROLE.admin` acts as a plain user there, by design. For a working
  sandbox administrator, create the admin in the **parent** and have it `postData(AEP.openSandbox, emptyMap())`,
  which moves that session to the person's own sandbox user (created on first use); `SandboxAccessTest.kt` is the
  reference.
- **Config edits in a sandbox act on the parent, as drafts** (issue #930). A sandbox has no configuration of its own:
  the `/clientAdmin/config/...` endpoints and the copy and menu editors, called in one, read and write the parent's,
  attributed to the person's parent user, and a write reloads the parent (so the sandbox shows it, while the
  parent -- published-only, as every client with a sandbox is -- keeps its published revision). The editors save a **draft** for any client with a sandbox
  (result `mode` = `EDM.draft`) and publish at once for one without. `SandboxEditsTest.kt` is the reference.
- **Source code may file an overlay under a sandbox** (issue #940): a config built with `client = sandboxOf(parent)`
  and the **parent's** namespace (`clientNamespace(parent)`), which the sandbox runs and the parent does not -- a
  per-client feature flag, promoted by moving it into the parent's source. It layers above the parent's source and
  below its stored revisions (so a stored copy or menu change wins over the overlay's); only a stored or imported
  config under a sandbox is refused. An overlay for a parent without the `sandbox` flag is reported on the parent and
  not loaded, and one repeating the parent's source is reported. `SandboxOverlayTest.kt` is the reference.
- **The sample's acme has a sandbox** (issue #994), so it is published-only: a sample test that stores config for
  acme -- through an editor or `writeConfig` -- publishes it before asserting what acme serves (`publishAcme` in the
  sample's tests does what the client page's Publish does). A scenario that needs a client without a sandbox, or one on
  the latest tier, takes a dynamic client of its own as above; the sample's globex has none either.

Stored config is **added beside** the source-declared config in the same collector, keyed by the client in its
id — downstream services can't tell a stored client from a source one.

## The HTTP path (driving it as an admin)

The same capability is exposed under the `clientAdmin` section (a `ROLE.admin` confined to the caller's own
client), issue #627 — the config id is always built from `cxt.client`, so a caller can only touch their own:

| Path (`CFEP`) | Method | Purpose |
| --- | --- | --- |
| `/clientAdmin/config/bundle/write` | POST | write a whole bundle (authoritative; `impliedDelete` default true) |
| `/clientAdmin/config/bundle/publish` | POST | publish the latest editable revision (`acknowledgeImpact` to go past an impact report) |
| `/clientAdmin/config/bundle/impact` | GET | what publishing it would do to the client's stored data (`IMP`, issue #935) |
| `/clientAdmin/config/reload` | POST | reload this client on this node |
| `/clientAdmin/config/bundle` / `/bundles` | GET | fetch one / list this client's configs |
| `/clientAdmin/config/publishedOnly` | POST | set the protection tier |

Drive them with a `TestUser` (`admin.postData(CFEP.reload, emptyMap())`); `GedraConfigEndpointTest.kt` is the
reference. **Publishing does not go live immediately** — it stamps `publishedAt`, changing which revision a
later reload/boot picks up for a **published-only** client. A client on the **latest** tier loads its latest revision
whether published or not. The tier is `publishedOnly(client) = toggled(client, env) || asksForSandbox(client)`
(`GedraConfigControl`; issue #930): a client whose source or **published** definition sets `sandbox = true` is
published-only whatever the toggle says, so a test wanting a client on the latest tier leaves the flag off.

**A publish can be refused over the client's stored data** (issue #935). For a published-only client the publish
endpoints compute an impact report (`ConfigImpact`): rows whose trait the publish drops, whose entries would stop
validating, or whose workflow or recorded task it removes. A non-empty report refuses the publish (`errorCode`
`IMP.refusedCode`, the report in `extraData`) unless the request sends `IMP.acknowledgeImpact = true`. A test that
publishes such a change on purpose acknowledges it; `GedraConfigService.publish` called directly checks nothing
unless asked (`impact = ImpactGate.refuse`). `ConfigImpactTest.kt` is the reference.

**`staticConfig`** is not a tier (issue #824): set only in source, it makes the client take nothing stored in
production -- writes refused, stored config ignored -- and leaves it an ordinary client everywhere else.

## How config reaches a node

- **On a running node:** `reloadClient` (above) — per client, live, and targets **only the node it runs on**.
- **At boot:** `GedraConfigLoadService` (a startup service ahead of `ClientService`/`SchemaService`) reads the
  tier-appropriate latest revision of every class and adds it to the collector, so a restarted **persistent**
  node picks up what a peer wrote. Loading is **off for in-memory** nodes unless `KDR_LOAD_STORED_CONFIG` forces
  it — an in-memory test builds its clients with `writeConfig` + `reloadClient` in-process instead.
- **Across a multi-node cluster:** `ClientSyncService` (issue #618) — the peers' fallback to a one-node reload. A
  reload (or boot load) **announces** its per-client marker into a shared `ClientSyncTracking` row (after a
  reload, `ClientSyncService.announceReload(cxt, result)`, which also announces a sandbox's own marker); every other
  node reads that row once per request (node-global throttle ~250ms, request-driven like the table caches, no
  timer) and reloads any client whose marker moved past what it last ran. So a change written and reloaded on one
  node reaches the rest without a per-node call. It is **off exactly when the boot load is off** (`loadEnabled`),
  so an in-memory single-instance test never relies on it — there, `reloadClient` on the one node is the whole
  story.

## Time travel

There is a real clock abstraction (issue #160), but read the scope carefully for scenario tests:

- **`InstanceClock`** (`instanceConfig.clock`) is **instance-wide**: `advanceBy` / `setAbsolute` / `freeze` /
  `unfreeze` / `reset`, read through `KdrCxt.instanceNow()`. In a test call it directly
  (`cxt.instanceConfig.clock.advanceBy(31.days)`) or POST the `forTestingOnly` `/fixture/clock` endpoint
  (`TCLK` + `ClockOp`). `TimeTravelTest.kt` is the reference for all five ops.
- **Persisted `createdAt`/`updatedAt` use `instanceNow()`** — so aging stored data means moving the instance
  clock.
- **The consequence for the shared-instance technique:** instance-wide travel moves time for **every** client at
  once. A test that ages data has to either take its own instance/DB or run the travel serially and `reset`.
- **Per-client time travel is a documented but unbuilt seam:** `KdrCxt.nowTimeOffsetInSeconds`
  (`now() = instanceNow() + offset`) is called out in its own KDoc as "the seam a future client-scoped clock
  would layer on," idle today. It would move a client's `now()` cheaply, but **not** its persisted timestamps
  (those read `instanceNow()`) — routing persistence through a per-client clock is the real work, and the reason
  per-client aging isn't free yet.

## Gotchas

- **Each instance name gets its own in-memory DB** (issue #836), so specs no longer see each other's rows —
  but every case sharing a spec's instance shares its DB, and so do instances pointed at one `KDR_DB_NAME`
  (`mkTestBootCxt("x", "x", mapOf("KDR_DB_NAME" to "mySpecDb"))`, the way a restart test gives both its boots
  one database). So still name clients uniquely: a durable scheme is to **suffix the client id with the issue
  number** — `narrowcap589`, the way Cedar suffixed test fixtures with the Jira number — which makes a collision
  essentially impossible without thinking about it. (A per-spec prefix works too; the examples above keep short
  names only for readability.) A user created with no `userClient` lands in the default client — `public`, or
  `hub` for one holding `allClients` (`createFullAdmin`, issue #799) or created at the `admin` level (issue
  #805: an administrator in `public` reaches only their own users, so a client admin belongs in a real client)
  — whose stored config accumulates across the spec's cases; prefer placing users in your own client. A scoped
  admin and the users it administers must share a client, so place both.
- **Collisions are strict in `unit`/`local`, degraded in production:** a client declaring a rooted trait id (a
  colon is the global side's, issue #921), a client declaring one `traitId` twice, or a config outside its own
  `client.<clientId>` namespace, fails the reload in a test (which is what `GedraConfigReloadTest`'s rollback case
  checks) but is tolerated live. A client's ids are bare and global ones rooted (`kdr:name`, issue #951), so a
  scenario client's `name` is its own trait beside core's, and two **different** clients may each declare the same
  `traitId` (issue #807) -- each gets its own -- so scenario clients need no prefixed ids.
- **`writeConfig` binds to the config's client** — a context bound to another client is not refused (the write
  re-binds it), and a `userId` is not required. It refuses the reserved `global` client, and any namespace but
  `client.<clientId>`, outright.

## Reference implementations and docs

- `base/kdn/src/test/.../GedraConfigReloadTest.kt` — the service path (write + reload + isolation + rollback).
- `base/kdn/src/test/.../GedraConfigEndpointTest.kt` — the HTTP path as an admin.
- `base/kdn/src/test/.../GedraConfigTierTest.kt` — the latest/published-only/static tiers.
- `base/common/.../gedra/GedraConfigService.kt` / `GedraConfigReload.kt` / `GedraConfigControl.kt` — the runtime.
- Design docs (repo root, also on the home page): `gedra-config-and-data.md` (the model), `client-definition.md`
  (the `ClientDef` spec), `gedra-workflow.md`.

For the schema DSL used inside a `trait`, see `kdr-schema-builder`; for booting and `TestUser`, `kdr-testing`.
