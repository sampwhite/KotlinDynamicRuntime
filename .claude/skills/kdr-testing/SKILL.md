---
name: kdr-testing
description: Test and verify changes in KotlinDynamicRuntime — booting your own server to drive it by curl or browser, and writing in-process unit tests. Covers the KDR_PORT/in-memory server conventions (and the don't-touch-7070 rule), mkTestBootCxt/mkBootCxt with config overlays, TestHttpClient and its response-extraction idioms, injecting env-var options through the instance config, and the TestUser/become-user helper for authenticated tests. Use whenever writing or reviewing a test, verifying a change end-to-end, or booting and driving the app in this codebase — even when the request just says "check that this works" or "run the app".
---

# Testing and verifying changes

There are two ways to confirm a change in this repo, and they share the same boot machinery underneath:

- **Manually** — boot your own instance and drive it with `curl` or the in-app browser. Best for HTTP/UI
  behavior and for anything you want to *see* end to end.
- **Unit tests** — exercise the request pipeline in-process with `TestHttpClient`, no sockets. Best for
  regression coverage and fast, deterministic checks.

Prefer doing both for a behavioral change: a focused unit test for the contract, and a quick live drive to
confirm it works in a running instance (that combination has caught things each alone missed).

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

**Stop it and free the port when done** (targeted, so you never touch 7070):

```bash
PID=$(lsof -i:7071 -sTCP:LISTEN -t); [ -n "$PID" ] && kill "$PID"
```

## Driving it with curl

```bash
# A success envelope carries requestUri/duration/contentHash + results/item/items.
curl -s http://localhost:7071/kda/app/ui/config | jq .

# Authenticate with a cookie jar. /test/becomeUser creates-or-finds a user and logs you in (test endpoints
# are on here because of KDR_IN_MEMORY_ONLY):
JAR=/tmp/cookies.txt
curl -s -c "$JAR" -X POST http://localhost:7071/kda/test/becomeUser -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","grantAdmin":true}' | jq .results
curl -s -b "$JAR" http://localhost:7071/kda/auth/self/info | jq .results   # now acts as alice
```

A **content differential** confirms a stable-vs-changing value: call twice unchanged (same `contentHash`),
then change an input (different `contentHash`). The `/demo/*` endpoints (`/demo/calc`, `/demo/todos`) are ideal
— pure, parameterized, no auth.

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

Worked examples: `DemoEndpointsTest`, `UiConfigEndpointTest`, `AppUiConfigEndpointTest` (all in `base/kdn`).

## Unit tests: config-resolution helpers, directly

For a pure config-resolution function (the `something(config): Boolean` shape), skip the boot and build a
config by hand — faster and clearer. `getEnvVar` reads **instance-config entries before the real process
environment**, so you can inject an "env var" with `put`:

```kotlin
val c = KdrInstanceConfig("t", ENV.local, ENV.liveSource).apply { put(SomeObj.someEnvVar, "true") }
SomeObj.resolves(c) shouldBe true
```

See `ErrorObfuscationConfigTest` and `AllowTestEndpointsTest` (both in `base/common`) for the three-way
(config option / env var / environment) resolution pattern.

## Unit tests: authenticated tests with TestUser

`TestUser` (in `base/common`, `user` package) is an authenticated `TestHttpClient` plus the `cxt` it was built
from. `TestUser.create(cxt, email, admin)` calls the `forTestingOnly` `/test/becomeUser` endpoint through a
fresh client — creating the user if needed and capturing the session cookie — so every call it makes is *as
that user*:

```kotlin
val alice = TestUser.create(cxt, "alice@example.com", admin = true)
alice.userId shouldBeGreaterThan 0L
alice.getData("/profile/ui/config")   // made as alice; getData/postData unwrap `results`
```

This works in unit tests because `env == unit` allows test endpoints. Test-only endpoints (marked
`forTestingOnly` on the builder) are dropped from the store unless a deployment allows them
(`SchemaService.allowTestEndpoints` = env var, `unit`, or `inMemoryOnly`), and a server that allows them
outside `local`/`unit` fails startup — so they can never reach a real environment. Add convenience methods to
`TestUser` as more involved multi-user simulations need them.
