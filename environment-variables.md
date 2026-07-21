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

---

## Application / runtime

| Variable | Purpose | Default |
| --- | --- | --- |
| `KDR_ENV` | The environment name — `local`, `dev`, `prod`, … Drives environment-specific behavior (e.g. whether the sample app loads, whether a database host is defaulted). | `local` |
| `KDR_IN_MEMORY_ONLY` | Default for the `inMemoryOnly` mode (parsed loosely as a boolean). When true, the runtime uses in-memory state and the database type is **forced** to in-memory H2. | `true` |
| `KDR_TEST_INSTANCE` | Force this to be a *test instance*, independent of environment — exposing `forTestingOnly` endpoints and simulating/capturing email by default. Also true implicitly when `KDR_ENV=unit` or `inMemoryOnly` is on. A test instance in an environment other than `local`/`unit` refuses to start, so test affordances cannot reach a real deployment. | unset (derived) |
| `KDR_CUSTOM_CONFIG` | The class name of the deployment configuration object to discover and apply at startup. | `KdrConfig` |
| `KDR_LOAD_SAMPLE` | Force-loads (`true`) or skips (`false`) the `sample` module's demo Todo endpoints. | on for `local`/`dev`, off otherwise |
| `KDR_ADMIN_EMAIL_DOMAIN` | Email domain whose addresses are automatically granted the `admin` role — how a deployment's first administrator comes to exist. An address qualifies when its domain **is** this domain (or a subdomain of it) **and** its local part carries no `+` tag, so `sam@acme.com` becomes an admin while `sam+qa@acme.com` stays an ordinary user, letting one mailbox hold both. Applied when a user is provisioned and re-checked at each login, so it reaches accounts registered before it was set. It only ever *grants*: unsetting it demotes nobody — revoke with `admin/user/setRoles` or `kdr-run com.dynamicruntime.script.GrantRoleKt <loginId> admin --revoke`. Unset means no address is ever auto-granted. | unset |

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
