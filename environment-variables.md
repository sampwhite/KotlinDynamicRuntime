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
| `KDR_CUSTOM_CONFIG` | The class name of the deployment configuration object to discover and apply at startup. | `KdrConfig` |
| `KDR_LOAD_SAMPLE` | Force-loads (`true`) or skips (`false`) the `sample` module's demo Todo endpoints. | on for `local`/`dev`, off otherwise |

## Logging

| Variable | Purpose | Default |
| --- | --- | --- |
| `KDR_LOG_LEVEL` | Log level for the application's own topics (`trace`/`debug`/`info`/`warn`/`error`/`off`). | `debug` |
| `KDR_ROOT_LOG_LEVEL` | Log level for everything else (third-party libraries). | `info` |
| `KDR_LOG_PATH` | Directory for the rolling log file. | `logs` |

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
| `NODE_IP_ADDRESS` | The node's IP identity, used in the node label. | `127.0.0.1` |
| `HOSTNAME` | The host name, used in the node label. | (system-provided) |
