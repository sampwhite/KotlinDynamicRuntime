# Environment Variables

This project deliberately leans on environment variables to vary configuration at startup (see the
"environment variables" note in [`code-guide.md`](code-guide.md)). This file is the reference for all of
them.

Conventions:

- **Prefix.** Every application variable uses the `KDR_` prefix (for *KotlinDynamicRuntime*).
- **Reading.** Values are read through `KdrCxt.getEnvVar`, which consults the running instance's
  configuration **before** the real process environment. A deployment's Kotlin config (or a test) can
  therefore set or override any of these without touching the actual environment.
- **Secrets are not environment variables.** Database passwords live in a secrets file, never in the
  environment or in config — see [Database](#database) below.
- **Booleans are parsed the same way everywhere**, through `KdrCxt.getEnvBool`: the first non-whitespace
  letter decides, case-insensitively — `true`/`yes`/`y`/`t`/`1` are true and `false`/`no`/`n`/`f`/`0` are
  false. Anything else means *unreadable*, not false: the variable falls through to its default, so a typo
  never silently flips a setting. (Three different parsers used to disagree here — one ignored `yes`, another
  read every spelling except `true` as false.)

---

## Application / runtime

| Variable | Purpose | Default |
| --- | --- | --- |
| `KDR_ENV` | The environment name — `local`, `dev`, `prod`, … Drives environment-specific behavior (e.g. whether the sample app loads, whether a database host is defaulted). | `local` |
| `KDR_IN_MEMORY_ONLY` | Default for the `inMemoryOnly` mode (parsed loosely as a boolean). When true, the runtime uses in-memory state and the database type is **forced** to in-memory H2. | `true` |
| `KDR_TEST_INSTANCE` | Force this to be a *test instance*, independent of environment — exposing `forTestingOnly` endpoints and simulating/capturing email by default. Also true implicitly when `KDR_ENV=unit` or `inMemoryOnly` is on. A test instance in an environment other than `local`/`unit` refuses to start, so test affordances cannot reach a real deployment. | unset (derived) |
| `KDR_CUSTOM_CONFIG` | The class name of the deployment configuration object to discover and apply at startup. | `KdrConfig` |
| `KDR_LOAD_SAMPLE` | Force-loads (`true`) or skips (`false`) the `sample` module's demo file upload/download endpoints. | on for `local`/`dev`, off otherwise |
| `KDR_GOOGLE_CLIENT_ID` | The deployment's Google OAuth **client id**, which turns Google sign-in on: unset (the default) and the feature is neither offered by the auth UI nor accepted by its endpoint. It is public by design — it identifies the application to Google and the browser must present it — so it is an environment variable rather than a secret, and the auth UI config serves it to the frontend. It is also what an incoming Google ID token's `aud` claim is checked against, which is the check that stops another application's tokens from being accepted here; there is deliberately no default, since a guessed value would defeat that check. **Setting it is not sufficient on its own:** every origin the page is served from must also be registered against that client id as an *Authorized JavaScript origin* in Google Cloud Console — see the note below. | unset |
| `KDR_OBFUSCATE_ERRORS` | Replaces the message of an error flagged `sensitive` — one that would reveal, say, whether an account exists — with a generic sentence before it reaches the client. The real message is still logged, so nothing is lost to whoever is entitled to see it. The `obfuscateSensitiveErrors` config option wins over this, and this wins over the default. | on when `KDR_ENV=prod`, off otherwise |
| `KDR_ADMIN_EMAIL_DOMAIN` | Email domain whose addresses are automatically granted the `admin` role — how a deployment's first administrator comes to exist. An address qualifies when its domain **is** this domain (or a subdomain of it) **and** its local part carries no `+` tag, so `sam@acme.com` becomes an admin while `sam+qa@acme.com` stays an ordinary user, letting one mailbox hold both. Applied when a user is provisioned and re-checked at each login, so it reaches accounts registered before it was set. It only ever *grants*: unsetting it demotes nobody — revoke with `admin/user/setRoles` or `kdr-run com.dynamicruntime.script.GrantRoleKt <loginId> admin --revoke`. Unset means no address is ever auto-granted. | unset |

Notes:

- **Google sign-in also needs the page's origin registered.** `KDR_GOOGLE_CLIENT_ID` turns the feature on, but
  Google checks the origin the page is served from against the *Authorized JavaScript origins* configured for
  that client id (Google Cloud Console → APIs & Services → Credentials → the OAuth 2.0 Client ID). An
  unregistered origin makes Google refuse the client id. **The symptom varies**, so do not go looking for one
  in particular: it may be a `403` on Google's `credential_button_library` request with
  `[GSI_LOGGER]: The given origin is not allowed for the given client ID` in the console; it may be
  **"Access blocked — You can't sign in to this app because it doesn't comply with Google's OAuth 2.0
  policy"** inside Google's own window after clicking; and it may be a button that renders perfectly and
  simply never completes. Nothing reaches this server in any of those cases, so there is no log line here and
  no misconfiguration the app can detect: the client id is valid, and only Google knows which origins go with
  it. On a test instance the auth page prints the origin it is served from, beside the button, so you have the
  exact string to register (issue #250).
- **An origin is scheme + host + port, and each is separate.** Register `http://localhost:7070` (the
  same-origin route, where `appui` serves the front end under `/wa`) and `http://localhost:8080` separately if
  the webpack dev server is also used — and with no path, so `http://localhost:7070`, never
  `http://localhost:7070/wa`. An instance on another port (`KDR_PORT=7071`, say) is another origin again; the
  simplest fix there is to leave `KDR_GOOGLE_CLIENT_ID` unset for that instance, which turns the button off
  rather than drawing one that cannot work.

## Logging

The application's own topics log through the two-way (KMP) `KdrLogger` to a stdout sink; third-party libraries
(Jetty, etc.) keep logging through log4j2, format-matched so the two look consistent. There is deliberately no
rolling-file appender — the app writes to stdout and a deployment tool captures/rolls it.

| Variable | Purpose | Default |
| --- | --- | --- |
| `KDR_LOG_LEVEL` | Log level for the application's own topics (`trace`/`debug`/`info`/`warn`/`error`/`off`). | `debug` |
| `KDR_ROOT_LOG_LEVEL` | Log level for everything else (third-party libraries, via log4j2). | `info` |
| `KDR_LOG_ASYNC` | Deliver our logs asynchronously (`true`) via a background worker, or synchronously (`false`). Sync gives immediate, ordered, crash-safe output (ideal for local/dev); async decouples the write off the caller's thread for production. Also selects ANSI color — on in sync mode, off in async. | `false` |

## Database

The database can be configured **entirely** from these variables plus the password secret. Once the type is
known, everything else is defaulted; in local development, `KDR_DB_TYPE=postgres` alone is enough.

| Variable | Purpose | Default |
| --- | --- | --- |
| `KDR_DB_TYPE` | The database kind: `h2Memory`, `h2File`, or `postgres`. Ignored when `inMemoryOnly` is true (which forces `h2Memory`). | `h2Memory` if `inMemoryOnly`, else `h2File` |
| `KDR_DB_NAME` | The database name — the H2 data file base name (`h2Database/<name>.dat`) and the PostgreSQL database. | `kdr` |
| `KDR_DB_HOST` | PostgreSQL host, with an optional `:port` suffix (e.g. `db.example.com:5433`). **PostgreSQL only.** | `localhost` **in the `local` environment only**; required in every other environment |
| `KDR_DB_USER` | PostgreSQL username. **PostgreSQL only** (the H2 variants use a hardcoded user). | `kdr` |
| `KDR_ALLOW_SCHEMA_DRIFT` | Boot despite **blocking** schema drift — a column the database has, the code does not declare, that is `NOT NULL` with no default. Startup normally refuses, because the framework cannot populate such a column and so every insert into that table already fails. Setting this true downgrades the refusal to a logged error; it does **not** make writes work. For an operator part-way through a migration. | `false` |
| `KDR_FRAGMENT_CHECK` | What a malformed Markdown fragment does at startup: `strict` refuses the boot, `warn` logs the problems and serves anyway, `off` skips the check. Unset means **strict everywhere except `prod`**, where it is `warn` — a developer or a test should be stopped by broken copy, while a production node should not refuse every unrelated endpoint over one malformed message (the render path already falls back for that one). Deliberately keyed on the environment rather than `isTestInstance`, which is inferred from in-memory-ness and so is false for an ordinary local run. Check a running node with `GET /operator/fragments/check`. | unset (`strict`, or `warn` in `prod`) |

Notes:

- **`inMemoryOnly` wins.** When `inMemoryOnly` is true, the type is forced to in-memory H2, overriding both
  `KDR_DB_TYPE` and any explicit database configuration.
- **Explicit config wins over the environment.** A deployment that configures the database in Kotlin (via
  `DatabaseConfigBuilder`) takes precedence over the `KDR_DB_*` variables.
- **Non-local hosts must be explicit.** `KDR_DB_HOST` is defaulted to `localhost` only in the `local`
  environment, as a guard against a deployed instance silently connecting to a local database. In any other
  environment, selecting PostgreSQL without `KDR_DB_HOST` is a startup (configuration) error.
- **The password is a secret, not a variable.** It is read from `private/secrets.properties` (relative to the
  [workspace directory](README.md#the-workspace)) under a property whose name defaults to `dbPassword`. A
  missing required secret fails startup. Only PostgreSQL needs one; the H2 variants require no password.
- **Schema changes only ever add.** Startup reconciles the declared tables against the database by adding
  missing columns and indexes; it never drops or renames anything, so **a rename is a written migration** and
  the old column stays until you remove it. Since issue #216 startup at least *tells* you: a stranded
  `NOT NULL` column refuses the boot naming the table and column (`KDR_ALLOW_SCHEMA_DRIFT` to start anyway),
  and a column the code marks required that the database still allows to be null is logged as a warning —
  normal right after a deploy that added it, a missing backfill if it persists.

## Table caches

A table small enough to fit in memory can be *cached* in it: `SqlTableCache` holds a copy and keeps it
current by asking, incrementally, for the rows changed since it last looked. `AuthUsers` is cached this way,
which is what stops every gated request from re-querying the acting user's row. Nodes tell each other about
changes through one shared row (`KdrCacheState`), read once per request, so an unchanged table costs nothing
beyond that read.

Neither variable normally needs setting; they exist because a cache is the kind of thing you want a lever on
at three in the morning, not a code change away.

| Variable | Purpose | Default |
| --- | --- | --- |
| `KDR_TABLE_CACHE_DISABLED` | Turns **every** registered table cache off. Each cached lookup then misses and falls back to the SQL query it was replacing, so the deployment loses the speed and nothing else — the escape hatch if a cache is ever suspected of serving stale data. | off |
| `KDR_TABLE_CACHE_MIN_RECHECK_MS` | How far back a cache reconsiders when the shared state row reports no change at all. It bounds staleness for a change nothing announced — rows written by a migration script or by hand — so lowering it makes such a change visible sooner at the cost of more reload queries. A change made *through the application* is unaffected: it is announced, and picked up immediately (on the writing node) or at the writer's next request end (on the others). | `30000` |

## Node identity

These control the running node's HTTP port and the identity shown in its label / health report.

| Variable | Purpose | Default |
| --- | --- | --- |
| `KDR_PORT` | The HTTP port the server binds to. Set this to run a second instance alongside another (e.g. an automated agent's server beside a developer's) without a port collision — usually together with `KDR_IN_MEMORY_ONLY=true` so the two do not contend on a database. A set-but-non-integer value fails startup. | `7070` |
| `KDR_NODE_IP_ADDRESS` | The node's IP identity, used in the node label. | `127.0.0.1` |
| `KDR_HOSTNAME` | The node's host name, used in the node label. Falls back to the OS `HOSTNAME` when unset. | the OS `HOSTNAME`, or `localhost` |

## Workspace / infrastructure

| Variable | Purpose | Default |
| --- | --- | --- |
| `KDR_WORKSPACE_DIR` | The [workspace directory](README.md#the-workspace) — the directory containing this repository, the per-deployment `settings.gradle.kts`, and runtime data. Setting it in a shell consistently controls both the `bin/` scripts and the launched JVM (handy with multiple checkouts of the same repository). | the nearest ancestor of the working directory that holds a `settings.gradle.kts` |

The system property `kdr.workspaceDir` is the test-overridable sibling of `KDR_WORKSPACE_DIR` and takes
precedence over it.

## Standard variables read

These are not defined by this project but are consulted when present:

| Variable | Purpose | Default |
| --- | --- | --- |
| `HOSTNAME` | The OS/container host name. Used for the node label only as a fallback when `KDR_HOSTNAME` is unset. | (system-provided) |
