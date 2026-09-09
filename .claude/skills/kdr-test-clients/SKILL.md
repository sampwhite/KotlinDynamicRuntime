---
name: kdr-test-clients
description: Set up an isolated test scenario by creating a NEW CLIENT (with its own traits, workflows, schema) on a SHARED instance, instead of booting a new instance per test. Because form docs, workflows, and users are isolated by a client boundary, a fresh client id is a clean namespace, so one booted node serves many scenarios. Covers the stored-config capability this rests on — the `gedraConfig` DSL (`defineClient`/`trait`/`workflow`/`cfact`), `GedraConfigService.writeConfig`, `GedraConfigReload.reloadClient` (making config live with no restart), the `/clientAdmin/config/{bundle,bundle/write,bundle/publish,reload,publishedOnly}` HTTP endpoints, and the free/published-only/static protection tiers (#617). Also covers placing a `TestUser` in the new client, the client-isolation boundary (client-in-id + ReadScope→SQL), and time travel (instance-wide `InstanceClock` today; per-client is a documented but unbuilt seam). Use when writing a test that needs its own client/workflow/trait/schema, when creating or editing a client/workflow/trait/schema in the database at runtime, or when deciding between a new instance and a new client for a scenario.
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

// A write is attributed, so bind a sub-context to the client with a userId before writing its config.
fun asClient(client: String): KdrCxt = cxt.mkSubContext("setup", client).also { it.userId = 9000L }

// Create a client dynamically: define it, give it a trait, persist the bundle, and reload it live.
fun createClient(client: String, trait: String) {
    val config = gedraConfig(cxt, "${client}cfg", "${client}config", client) {
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
ClientService.get(cxt).known("scenarioA").shouldNotBeNull()                          // the client is present

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
you never author into it (`writeConfig` refuses the `global` client and the `globalconfig` namespace).

## Narrowing a schema after data is captured (no restart)

Testing that a **schema change invalidates already-captured data** — a stored value a later, tighter schema
rejects — used to need a persistent H2 store and two builds (capture on the wide schema, rebuild on the narrow
one, reopen). `reloadClient` replaces the whole dance: the schema narrows **live on one running node**, so a
value valid at capture is rejected once the reload lands. Transcribed and compiled in the same
`TestClientSkillExamplesTest`:

```kotlin
val client = "narrowcap"
fun writeClient(vararg topics: String) {
    val config = gedraConfig(cxt, "${client}cfg", "${client}config", client) {
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
unpublished, so no `publish` is needed for a free-tier client — `reloadClient` picks up the narrowed revision.
Because there is no restart, the captured row is never wiped, so persistence is no longer the point (it was only
ever there to survive the rebuild). Prefer this to the persistent-H2 two-build pattern for any "captured data a
later schema rejects" test.

## The building blocks

- **`gedraConfig(cxt, name, namespace, client) { … }`** (`base/kernel/.../gedra/GedraConfig.kt`) builds one
  config **bundle** for one client. Inside the block:
  - **`defineClient(ClientDef(...))`** — exactly one per bundle (a second throws). `ClientDef` needs
    `clientId`, `name`, `usageType` (`ClientUsageType`), `audience` (`ClientAudience`), and
    `enabledEnvironments` (include `ENV.unit` and `ENV.local` so a test node loads it). `staticConfig = true`
    pins it to source-only, published-only.
  - **`trait(typeName, traitId, appliesTo, description) { <SchTypeBuilder> }`** — a trait whose data shape is
    written inline; the `{ … }` is the `kdr-schema-builder` DSL. An overload takes `dataType` (a `$ref`) instead.
  - **`workflow(workflowId, entry: WfEntry) { <WfDefBuilder> }`** — a workflow definition (or `workflowFromMap`
    for the JSON form). See the compile-time `createForm` workflow in `sample/.../SampleClients.kt` for the
    shape, and `gedra-workflow.md` for the model.
  - **`stateTrait(...)`, `cfact(name, group, description, toFrontend)`** — workflow state traits and the cfacts
    the client may name in expressions.
  - A `schemaDef` slot exists for plain schema types too; most schema rides in via a trait's data shape.
- **`GedraConfigService.get(cxt).writeConfig(clientCxt, config)`** persists the bundle. `clientCxt` must be
  bound to the client and carry a `userId` (attribution) — hence `asClient(...)`. Writing is revision-aware
  (issue #611): first write is version 1; rewriting an unpublished latest edits in place; writing after a
  publish mints the next revision.
- **`GedraConfigReload.reloadClient(cxt, client)`** is *the* thing that makes config dynamic. It withdraws the
  client's previously-loaded stored config, adds the new through the same boot checks (rolling back on any
  throw), then swaps each service's derived state — `SchemaService.reloadClient`, `ClientService.recheck`
  (which is how a *newly created* client becomes `known`/`present`), the fragment/UiBlock overlays, and
  `WorkflowService.reloadClient`. A request in flight keeps the store it started with; the next request sees the
  new one. **No restart.**

Stored config is **added beside** the source-declared config in the same collector, keyed by the client in its
id — downstream services can't tell a stored client from a source one.

## The HTTP path (driving it as an admin)

The same capability is exposed under the `clientAdmin` section (a `ROLE.admin` confined to the caller's own
client), issue #627 — the config id is always built from `cxt.client`, so a caller can only touch their own:

| Path (`CFEP`) | Method | Purpose |
| --- | --- | --- |
| `/clientAdmin/config/bundle/write` | POST | write a whole bundle (authoritative; `impliedDelete` default true) |
| `/clientAdmin/config/bundle/publish` | POST | publish the latest editable revision |
| `/clientAdmin/config/reload` | POST | reload this client on this node |
| `/clientAdmin/config/bundle` / `/bundles` | GET | fetch one / list this client's configs |
| `/clientAdmin/config/publishedOnly` | POST | set the protection tier |

Drive them with a `TestUser` (`admin.postData(CFEP.reload, emptyMap())`); `GedraConfigEndpointTest.kt` is the
reference. **Publishing does not go live immediately** — it stamps `publishedAt`, changing which revision a
later reload/boot picks up for a **published-only** client. A **free-tier** client's latest revision loads
whether published or not; **static** (`staticConfig`) is source-only. The runtime collapses the ladder to
`publishedOnly(client) = staticConfig(client) || toggled(client, env)` (`GedraConfigControl`).

## How config reaches a node

- **On a running node:** `reloadClient` (above) — per client, live, and targets **only the node it runs on**.
- **At boot:** `GedraConfigLoadService` (a startup service ahead of `ClientService`/`SchemaService`) reads the
  tier-appropriate latest revision of every class and adds it to the collector, so a restarted **persistent**
  node picks up what a peer wrote. Loading is **off for in-memory** nodes unless `KDR_LOAD_STORED_CONFIG` forces
  it — an in-memory test builds its clients with `writeConfig` + `reloadClient` in-process instead.
- **Across a multi-node cluster:** `ClientSyncService` (issue #618) — the peers' fallback to a one-node reload. A
  reload (or boot load) **announces** its per-client marker into a shared `ClientSyncTracking` row; every other
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

- **The default in-memory DB is shared across specs in a JVM,** so name your clients uniquely. A durable scheme
  is to **suffix the client id with the issue number** — `narrowcap589`, the way Cedar suffixed test fixtures
  with the Jira number — which makes a cross-spec collision essentially impossible without thinking about it.
  (A per-spec prefix works too; the examples above keep short names only for readability.) Or give the spec its
  own DB with a `KDR_DB_NAME` overlay (`mkTestBootCxt("x", "x", mapOf("KDR_DB_NAME" to "mySpecDb"))`). A user
  created with no `userClient` lands in `public`, whose stored config accumulates across specs — prefer placing
  users in your own client.
- **Collisions are strict in `unit`/`local`, degraded in production:** two clients claiming one `traitId`, or a
  namespace with two owners, fails the reload in a test (which is what `GedraConfigReloadTest`'s rollback case
  checks) but is tolerated live.
- **`writeConfig` needs a client-bound context with a `userId`** — an unattributed or wrong-client context is
  refused, and it refuses the reserved `global` client / `globalconfig` namespace outright.

## Reference implementations and docs

- `base/kdn/src/test/.../GedraConfigReloadTest.kt` — the service path (write + reload + isolation + rollback).
- `base/kdn/src/test/.../GedraConfigEndpointTest.kt` — the HTTP path as an admin.
- `base/kdn/src/test/.../GedraConfigTierTest.kt` — the free/published-only/static tiers.
- `base/common/.../gedra/GedraConfigService.kt` / `GedraConfigReload.kt` / `GedraConfigControl.kt` — the runtime.
- Design docs (repo root, also on the home page): `gedra-config-and-data.md` (the model), `client-definition.md`
  (the `ClientDef` spec — its "Nothing here is built" header is stale), `gedra-workflow.md`.

For the schema DSL used inside a `trait`, see `kdr-schema-builder`; for booting and `TestUser`, `kdr-testing`.
