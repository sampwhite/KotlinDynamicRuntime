---
name: kdr-testing
description: Test and verify changes in KotlinDynamicRuntime — booting your own server to drive it by curl, kdr-probe or browser, and writing in-process unit tests. Covers -Pwebapp.dev=true for a readable frontend bundle when a crash reports minified nonsense. Covers the KDR_PORT/in-memory server conventions (and the don't-touch-7070 rule), the kdr-probe scenario host for driving a running instance as a chosen caller (and extending it, which needs no permission), the _debug=explainAccess tag for why a caller cannot see an endpoint, mkTestBootCxt/mkBootCxt with config overlays including ACFG.isTestInstance to test how a real node behaves, the focused-vs-flow test split and the conventions a shared-instance flow test needs, TestHttpClient and its response-extraction idioms, injecting env-var options through the instance config, selecting your own config object via KDR_CUSTOM_CONFIG to set config values that have no env var, and the TestUser/become-user helper for authenticated tests. Use whenever writing or reviewing a test, verifying a change end-to-end, or booting and driving the app in this codebase — even when the request just says "check that this works" or "run the app".
---

# Testing and verifying changes

There are two ways to confirm a change in this repo, and they share the same boot machinery underneath:

- **Manually** — boot your own instance and drive it with `curl` or the in-app browser. Best for HTTP/UI
  behavior and for anything you want to *see* end to end.
- **Unit tests** — exercise the request pipeline in-process with `TestHttpClient`, no sockets. Best for
  regression coverage and fast, deterministic checks.

Prefer doing both for a behavioral change: a focused unit test for the contract, and a quick live drive to
confirm it works in a running instance (that combination has caught things each alone missed).

## Running the suite

**`./gradlew check`** from the workspace root is the whole suite. Use it before claiming a change is green.
`bin/kdr-tests` runs exactly that.

**`./gradlew test` is the tempting wrong answer**: `test` comes from the JVM plugin, so it does not exist at
all in the multiplatform modules — `:base:kernel` and `:webapp` expose their tests as
`jvmTest`/`jsNodeTest`/`allTests`. Gradle runs a named task wherever it exists and says nothing about the
projects lacking it, so `test` leaves both modules' results *simply absent* — no failure, no mention. Note
that costs `:base:kernel`'s **JVM** tests too, not only frontend ones. While iterating, narrow with a module
task instead: `./gradlew :base:kdn:test --tests '*AuthFlowTest*'`.

If the build dies at **`:kotlinStoreYarnLock`** ("Lock file was changed"), note the catch-22: the remedy task
`kotlinUpgradeYarnLock` cannot run, because the store task fails the build before it is reached. Break the
cycle by forcing a re-resolve first:

```bash
./gradlew clean kotlinUpgradeYarnLock --rerun-tasks
```

The lock lives at the workspace root (`kotlin-js-store/yarn.lock`), outside the versioned repo, so each
workspace drifts on its own and every checkout hits this independently.

## Booting your own server (manual)

Run Gradle from the **workspace root** — the parent of the versioned repo, where the live
`settings.gradle.kts` and `gradlew` sit. Never hardcode that path: it differs per checkout. Resolve it the
way `bin/_common.sh` does — from `KDR_WORKSPACE_DIR` if set, else the nearest ancestor holding a
`settings.gradle.kts`. Start your **own** instance on a free port; do **not** bind or kill port `7070`, which
is the developer's IntelliJ instance.

```bash
# Resolve the workspace root (run from anywhere inside the checkout).
WS="${KDR_WORKSPACE_DIR:-$(d=$PWD; while [ "$d" != / ] && [ ! -f "$d/settings.gradle.kts" ]; do d=$(dirname "$d"); done; echo "$d")}"

cd "$WS" && KDR_PORT=7071 KDR_IN_MEMORY_ONLY=true ./gradlew :launch:run > /tmp/srv.log 2>&1 &
# wait for it, then hit it:
for i in $(seq 1 180); do curl -sf http://localhost:7071/kda/health >/dev/null && { echo up; break; }; sleep 1; done
```

The `bin/` wrappers do this resolution for you, so `KDR_PORT=7071 KDR_IN_MEMORY_ONLY=true kdr-backend` is the
same boot from any directory if `bin/` is on your `PATH`.

- `KDR_PORT` moves off 7070 (any free port). `KDR_IN_MEMORY_ONLY=true` uses in-memory H2, so there is no
  database contention — omit it only when the test needs a specific database or its content.
- `:launch:run` rebuilds and embeds the current `:webapp` bundle, so it serves your frontend changes at `/wa`.
- API endpoints are under the **`/kda`** context root (`/kda/health`, `/kda/auth/self/info`, …).
- **Env vars flip options at boot** — the most useful lever for testing a config-gated behavior without
  touching code: `KDR_IN_MEMORY_ONLY`, `KDR_PORT`, `KDR_OBFUSCATE_ERRORS` (obfuscate sensitive errors),
  `KDR_TEST_INSTANCE` (mark a test instance: expose `forTestingOnly` endpoints, simulate email). See `environment-variables.md` for the full
  list.
- **A config value with no env var** (a UI tuning value like a refresh interval, or any `AppConfigBuilder`
  property) — set it in your *own* config object and select it with `KDR_CUSTOM_CONFIG=ClaudeConfig`, so you
  never edit the developer's `KdrConfig` (their run's config can't break yours, and vice versa). Full recipe,
  addressed to you, in the **"For Claude"** section of `examples/custom-config.md`.

**A crash in the frontend reports minified nonsense by default.** The deployed bundle is webpack's production
build, so a Kotlin exception arrives with no `message` and a mangled `name` — a caught render failure reports
itself as `ji` at a byte offset. Add **`-Pwebapp.dev=true`** to embed the *readable* build instead (issue #230):

```bash
cd "$WS" && KDR_PORT=7071 KDR_IN_MEMORY_ONLY=true ./gradlew :launch:run -Pwebapp.dev=true
```

The same crash then reports `IllegalStateException … at DebugFault$lambda`, naming the Kotlin declaration. Same
filename, same URL, same behavior — only the readability differs. Reach for it the moment a frontend failure is
not obvious; it costs a rebuild and about 24 MB of bundle (vs 2 MB), so it is a troubleshooting build, not a
default. It is **one or the other per build**: Kotlin/JS runs both executable modes through a single
compile-sync directory, so a single invocation cannot produce both.

Pair it with the debug fault routes (issue #227) to make the app fail on demand:
`#page=debug&tool=fault` for a page failure, `#<any page>&fault=shell` for the shell.

**Stop it and free the port when done** (targeted, so you never touch 7070):

```bash
PID=$(lsof -i:7071 -sTCP:LISTEN -t); [ -n "$PID" ] && kill "$PID"
```

## Driving it with curl

```bash
# A success envelope carries requestUri/duration/contentHash + results/item/items.
curl -s http://localhost:7071/kda/app/ui/config | jq .

# Authenticate with a cookie jar. /fixture/becomeUser creates-or-finds a user and logs you in (test endpoints
# are on here because of KDR_IN_MEMORY_ONLY):
JAR=/tmp/cookies.txt
curl -s -c "$JAR" -X POST http://localhost:7071/kda/fixture/becomeUser -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","level":"admin"}' | jq .results
curl -s -b "$JAR" http://localhost:7071/kda/auth/self/info | jq .results   # now acts as alice
```

A **content differential** confirms a stable-vs-changing value: call twice unchanged (same `contentHash`),
then change an input (different `contentHash`). `/demo/schema/sample` (POST) and `/fixture/schema/complex` (PUT) are ideal
— pure, parameterized, no auth.

**Reach for `kdr-probe` (below) as soon as more than one caller is involved.** The cookie-jar form above is
fine for a single ad-hoc call and treacherous past that: a jar passed through an unquoted shell variable never
reaches `curl`, every request silently runs anonymous, and the output is a plausible table rather than an
error. That has happened, and the wrong answer cost far more than a crash would have.

**Why can this caller not see that endpoint?** `_debug=explainAccess` answers it directly, on
`/schema/endpoints` and `/schema/endpoint` (issue #215). Under `_meta.accessExplained` it reports the roles the
catalog filter actually compared — *after* the live-role refresh, which is the value worth seeing — and every
withheld endpoint grouped by section with the role that section demands:

```bash
curl -s -b "$JAR" 'http://localhost:7071/kda/schema/endpoints?_debug=explainAccess' | jq '._meta.accessExplained'
```

It is **test-instance only**, silently: on a real node the key is simply absent, because naming the privileged
surface to any caller who asks would undo the hiding it exists to explain. Without it, a filtering bug shows up
only as a count one lower than expected.

## Probing a running instance (`kdr-probe`)

`bin/kdr-probe` drives a **running** server as a chosen caller (issue #215). Start one first, then:

```bash
kdr-probe                                             # lists the scenarios
kdr-probe catalog-diff                                # what each rung is shown by /schema/endpoints
kdr-probe access-matrix /health /admin/users          # callers x paths -> status codes
kdr-probe grant-then-call                             # grant a rung to a live session, re-probe it
kdr-probe call --as operator GET /operator/system/info # one request, no session to keep
kdr-probe --url http://localhost:7099 catalog-diff    # somewhere other than the default 7071
```

**The rule that keeps it honest: one call may be flags; more than one call is a scenario in Kotlin, never
shell.** A multi-call check needs session state across those calls, and composing one-shot invocations
externally puts that state back into cookie files threaded through shell — relocating the mistake the tool
exists to retire, with argument quoting added on top.

**It is yours to extend, at will and without asking.** Adding a scenario is a function in `ProbeScenarios.kt`
and a line in `Probe.scenarios` — deliberately routine, not a design event. A check you are about to do for the
**second** time is already a scenario; the hand-rolled version is the one that silently lies. And the cost is
lower than it looks: wall clock here is dominated by the server boot, so the scenario can be written *during*
the boot you would be waiting through anyway. Reuse decides, not effort — genuinely single-use exploration
should use `call` rather than growing the registry.

**Scenarios report; they do not assert.** Anything worth asserting belongs in kotest, where it runs on every
`check`. `ProbeSession`'s method names echo `TestHttpClient`'s precisely so a scenario that earns its keep can
be promoted into a test with mechanical edits rather than a rewrite.

**Read the last line.** Every run ends with `kdr-probe: completed <name>` or `kdr-probe: FAILED <name> — …`,
and a `FAILED` line distinguishes an instance that could not be reached from a defect in the probe itself.
Scenarios print as they go, so a run that dies partway leaves output that reads as a short but finished report
— and piping through `grep` or `tail` loses the exit code. **Absence of the completion line means the report is
incomplete however complete it looks.**

## Traveling the clock (`/fixture/clock`)

Anything gated on elapsed time — a session lapsing, a rate-limit window reopening, a device's trust running
out — is otherwise unreachable: the shortest of those horizons is fifteen minutes and the longest is thirty
days. `/fixture/clock` moves the **instance** clock instead, so the expiry fires now. It is `forTestingOnly`, so
it does not exist outside a test instance; there is no way to move a real deployment's clock.

Five ops, POSTed as `op`: `advance` (with `deltaMs`), `set` (with `atMs`, epoch millis), `freeze`, `unfreeze`,
`reset`. Each returns `instanceNowMs`, the clock's value afterwards.

```bash
# Watch a live session go stale: log in, jump past the thirty-day session, then look again.
JAR=/tmp/cookies.txt
curl -s -c "$JAR" -X POST http://localhost:7071/kda/fixture/becomeUser -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com"}' > /dev/null
curl -s -X POST http://localhost:7071/kda/fixture/clock -H 'Content-Type: application/json' \
  -d '{"op":"advance","deltaMs":2592100000}' | jq .results
curl -s -b "$JAR" http://localhost:7071/kda/auth/self/info | jq .results   # now anonymous
```

This is the endpoint's real purpose: driving a **browser** session forward to watch the UI react to an expiry
you would otherwise have to wait a month for. `freeze` is the one to reach for when an assertion is
duration-sensitive — it holds the clock so repeated reads are identical instead of racing.

In a **unit test**, skip the endpoint and move the clock directly — `cxt.instanceConfig.clock.advanceBy(…)`.
Same clock, one less round trip. Whichever you use, in a flow test the clock only ever moves **forward** (see
the conventions above): `now()` also stamps persisted `createdAt`/`touchedAt`, so a rewind future-dates rows
already written. `AuthFlowTest` and `TimeTravelTest` are the worked examples.

## Driving the frontend (browser)

`:launch:run` serves the webapp at `http://localhost:7071/wa`. Use the in-app browser tools
(`navigate`/`read_page`/`computer`/`find`/`get_page_text`) to drive it, and `read_console_messages`
(`onlyErrors: true`) to confirm a clean run.

- The **auth session cookie (`kdrAuth`) is httpOnly** — you cannot read or clear it from `document.cookie`. To
  simulate a session going invalid, call the logout endpoint directly with `fetch('/kda/logout', {credentials:
  'same-origin'})` (which the app doesn't know about), then navigate to force a refresh and watch the UI redraw.
- Config re-fetches ride the refresh generation, so a **navigation** is usually what makes the app pick up a
  state change — click a menu link rather than expecting an idle page to update.

## Unit tests: booting an instance in-process

`Startup` (in `base/kdn`) boots the whole application without a server and hands back a `KdrCxt`:

- **`Startup.mkTestBootCxt(cxtName, instanceName, overlay)`** — the normal test entry. It forces `env = unit`,
  defaults `inMemoryOnly = true`, and turns on `validateResponseSchema` (so a response that doesn't match its
  output schema fails the test). The **`overlay`** map sets instance-config options for the test — this is how
  you exercise a config-gated path:

  ```kotlin
  val cxt = Startup.mkTestBootCxt("obfOn", "obfTest", mapOf(ACFG.obfuscateSensitiveErrors to true))
  ```

- **`Startup.mkBootCxt(...)`** — the raw boot, no unit-env forcing. Use it to test behavior in a **non-unit**
  environment, e.g. a startup guard: `mkBootCxt("g", "gI", mapOf(ACFG.env to ENV.dev, ACFG.inMemoryOnly to true))`.

- **Use a unique `instanceName` per test.** `InstanceRegistry` caches an instance by name, so a reused name
  returns the earlier config and silently ignores your overlay.

- **`ACFG.isTestInstance` in the overlay decides that flag outright** (issue #215), and is the only way to
  test how a **real** node behaves — `forTestingOnly` endpoints absent from the store, test-only debug output
  withheld. Everything else about the flag is inferred through a chain of ORs that can only ever say *yes*: a
  test runs in `ENV.unit` **and** in memory, and either alone re-asserts it, so setting the env var false
  changes nothing.

  ```kotlin
  val cxt = Startup.mkTestBootCxt("prod", "prodShapedTest", mapOf(ACFG.isTestInstance to false))
  cxt.instanceConfig.isTestInstance shouldBe false   // assert the premise, or the test proves nothing
  ```

  That premise assertion is the point, not ceremony: a fence test that silently ran on a test instance would
  pass for the wrong reason, and a fence test that cannot fail is worse than none. Note the consequence —
  `becomeUser` is gone in such an instance, so the caller is anonymous.

## Two kinds of test: focused, and flow

Most tests are **focused**: limited setup, one behavior, their own instance, as above. But a per-test instance
throws away everything that accumulates — elapsed time, prior logins, prior failures, another user's rows —
and that is exactly where feature-interaction bugs live. It also pays the setup cost again per assertion.

So behavior that depends on *accumulated* state goes in a **flow test** instead (`AuthFlowTest` is the worked
example): one instance declared at spec level, many named blocks running in declaration order as a single
continuous session. Blocks stay separately named so a failure still says what broke; what you trade away is
order-independence, since a block that fails takes the ones after it with it.

Three conventions make a flow test survivable — all of them learned from `AuthFlowTest`:

- **The clock only moves forward.** `cxt.instanceConfig.clock.advanceBy(...)` (issue #160) is a one-way
  ratchet. `now()` also stamps persisted `createdAt`/`touchedAt`, so a rewind future-dates rows already
  written and surfaces later as an unrelated failure.
- **Each block owns its identifiers** — its own contact, username and source IP — so one block's per-contact
  and per-IP rate-limit keys cannot silently throttle the next. Reuse an earlier block's user only on purpose,
  and say so in a comment.
- **Unwrap responses through a helper that reports the error envelope**, not a bare `getValue("results")`.
  Ten steps in, "expected a success but got 400: …" is the difference between a two-minute fix and a hunt.

The hard boundary: a test needing a **different instance-config overlay** can never join a flow — the unit of
sharing is the instance config. Boot it separately, in the same spec if it reads better there.

## Unit tests: TestHttpClient

`TestHttpClient(cxt.instanceConfig)` (in `base/common`, main source — usable from any module's tests) drives
the in-process pipeline. It **carries cookies across calls**, so a login on one call authenticates the next.

- `sendJsonGetRequest(path, args?)`, `sendJsonPostRequest(path, body)`, `sendJsonPutRequest(path, body)` →
  the parsed response envelope map. Paths are the endpoint's own (`/auth/self/info`); the client prepends `/kda`.
- `sendGetRequest`/`sendEditRequest(path, args, data, isPut)` → the `RequestHandler`, whose `rptStatusCode`
  lets you assert an error status (a validation failure is 400, a missing resource 404).
- Pull the payload out of the envelope with the shared conversion helpers, exactly as the existing tests do:

  ```kotlin
  fun results(resp: Map<String, Any?>) = resp.getValue(EP.results)!!.toJsonMap()
  fun Map<String, Any?>.obj(key: String) = getValue(key)!!.toJsonMap()   // a nested object
  fun Map<String, Any?>.list(key: String) = getValue(key) as List<*>      // items
  ```

Worked examples: `SchemaComplexEndpointTest`, `UiConfigEndpointTest`, `AppUiConfigEndpointTest` (all in `base/kdn`).

## Unit tests: config-resolution helpers, directly

For a pure config-resolution function (the `something(config): Boolean` shape), skip the boot and build a
config by hand — faster and clearer. `getEnvVar` (and `getEnvBool`, which every boolean variable is read
through) reads **instance-config entries before the real process
environment**, so you can inject an "env var" with `put`:

```kotlin
val c = KdrInstanceConfig("t", ENV.local, ENV.liveSource).apply { put(SomeObj.someEnvVar, "true") }
SomeObj.resolves(c) shouldBe true
```

See `ErrorObfuscationConfigTest` and `AllowTestEndpointsTest` (both in `base/common`) for the three-way
(config option / env var / environment) resolution pattern.

## Unit tests: authenticated tests with TestUser

`TestUser` (in `base/common`, `user` package) is an authenticated `TestHttpClient` plus the `cxt` it was built
from. `TestUser.create(cxt, email, level)` calls the `forTestingOnly` `/fixture/becomeUser` endpoint through a
fresh client — creating the user if needed and capturing the session cookie — so every call it makes is *as
that user*. `level` is a rung of the privilege ladder (`ROLE.user`, the default, `ROLE.operator` or
`ROLE.admin`, each including the ones below it) and applies only to a user being **created**: becoming an
existing user gets you whoever is already there, roles and all.

```kotlin
val alice = TestUser.create(cxt, "alice@example.com", level = ROLE.admin)
alice.userId shouldBeGreaterThan 0L
alice.getData("/profile/ui/config")   // made as alice; getData/postData unwrap `results`

val opal = TestUser.create(cxt, "opal@example.com", level = ROLE.operator)
opal.getData("/operator/system/info")            // an operator section
opal.expectError(EXC.notAuthorized, ADEP.users)  // ...but not an admin one
```

This works in unit tests because `env == unit` allows test endpoints. Test-only endpoints (marked
`forTestingOnly` on the builder) are dropped from the store unless a deployment allows them
(`SchemaService.allowTestEndpoints` = env var, `unit`, or `inMemoryOnly`), and a server that allows them
outside `local`/`unit` fails startup — so they can never reach a real environment. Add convenience methods to
`TestUser` as more involved multi-user simulations need them.
