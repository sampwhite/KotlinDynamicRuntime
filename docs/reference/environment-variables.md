<!-- kdr-generated: begin -- copied from its generator by `./gradlew :launch:refDocsDump`; edit the generator, not this -->
# Environment variables

Every environment variable the runtime reads, by area, with what it does and what applies when it is unset. A variable is read only through its declaration in the code, so this list is complete: a name not here is not read. A deployment reads only the variables of the components it carries.

A variable is set in the process environment, or as an entry of the instance config, which wins over it; a workspace's `default-environment-variables.properties` supplies those the environment leaves unset (see `KDR_DEFAULTS_FILE`). On a node with a boot role, a name carrying the role wins over the plain one: `KDR_EDGE_PORT` over `KDR_PORT` on an edge. A deployment flavor may default a variable nothing else sets. A running node's operator view, `GET /operator/env/reference`, lists the variables it has read, with the value each resolved to and where the value came from.

## Application

### `KDR_ACCESS_TOKEN_HOURS`

**Default when unset:** 24 (one day)

How long an access token exchanged for an API key lasts, in hours, and never past the key's own expiry. A service that verifies tokens with the published keys alone honors one until it expires, even after its key is revoked, so shorter is safer. A value that is not a whole number from 1 to 168 is ignored.

### `KDR_ADMIN_EMAIL_DOMAIN`

**Default when unset:** unset (no address auto-granted)

Email domain whose addresses are automatically granted the `admin` role -- how a deployment's first administrator comes to exist. An address qualifies when its domain is this domain (or a subdomain of it) and its local part carries no `+` tag. Applied at provisioning. It only ever grants: unsetting it demotes nobody. Unset means no address is ever auto-granted. See `AdminRules`.

### `KDR_API_KEY_DAYS`

**Default when unset:** 365 (one year)

How long a new API key lasts, in days, when its maker asks for no other. Held to KDR_API_KEY_MAX_DAYS. A value that is not a whole number of at least 1 is ignored.

### `KDR_API_KEY_MAX_DAYS`

**Default when unset:** 730 (two years)

The furthest from today an API key's expiry may be, in days, when it is made or extended; it also caps KDR_API_KEY_DAYS. A value that is not a whole number of at least 1 is ignored.

### `KDR_ASSUME_ENV_AUTH`

**Default when unset:** on for a test instance in `local` only (not `unit`)

Whether this node invents env auth for a request that arrived with no `X-Forwarded-For` -- the convenience that makes a developer's box behave like one behind an edge. The identity is synthetic (`assumed@local.invalid`). Independent of `KDR_TRUST_ENV_AUTH_HEADER`. The missing forwarded-for is not what makes it safe; the test-instance fence is. The `assumeEnvAuth` config option wins over this. A public test deployment may set it only when it listens on loopback alone (`KDR_BIND_ADDRESS`, issue #1245), behind a proxy that always sets `X-Forwarded-For`.

### `KDR_AUTH_CONCURRENCY`

**Default when unset:** 32

How many sign-in steps -- the logins, registration, the code mails, the claim and the invitation -- this node works on at once (issue #1299); one past it is answered 429, to be retried. The steps are held for the auth timing floor where errors are obfuscated, and a password hash is CPU-bound, so this bounds what a burst of them can cost. The auth section's cheap reads are not counted. Per node, so a cluster multiplies it. A positive whole number.

### `KDR_COOKIE_SECURE`

**Default when unset:** secure everywhere but `local`/`unit`

Whether the `Set-Cookie`s this node issues carry the `Secure` attribute -- session cookies (`kdrAuth`, and the edge's `kdrEnvAuth`) included. Set explicitly rather than derived from `X-Forwarded-For`: a cookie's `Secure` flag should be a property of how the node is served, not read off a request header. The `cookieSecure` config option wins over this. Unset, it defaults from the environment -- `Secure` everywhere except `local`/`unit`, so a plain-HTTP `localhost` keeps getting a cookie the browser will return. See `CookieRules`.

### `KDR_CUSTOM_CONFIG`

**Default when unset:** `KdrConfig`

**Read before deployment flavors resolve**, so a flavor cannot default it.

The `providerName` of the deployment configuration object (an `AppConfigApplier`) to discover and apply at startup. Full profiles are selected, not composed, so this names the one to apply -- defaulting to `KdrConfig`.

### `KDR_DEFAULTS_FILE`

**Default when unset:** unset (the workspace's default-environment-variables.properties)

**Read before deployment flavors resolve**, so a flavor cannot default it.

Which defaults file this process reads for variables the real environment does not set. Unset reads the workspace's `default-environment-variables.properties`; a path reads that file instead (a relative path resolves against the workspace); `none` reads no file at all, so the process runs on the real environment and the built-in defaults alone. For a process that must not inherit another developer's workspace choices -- an agent's server in a human-owned workspace, which otherwise picks up every value in that file, including ones added after it last looked. Read from the real environment only; setting it inside a defaults file has no effect.

### `KDR_DEPLOY_FLAVORS`

**Default when unset:** unset (no flavors)

**Read before deployment flavors resolve**, so a flavor cannot default it.

The deployment flavors this node runs with, comma-separated in order -- for example `reference`. A flavor is a named package of configuration changes a component declares; a later flavor wins where two set the same value. `none` says no flavors. A name no component declares refuses the boot. The operator view `/operator/flavors` lists what each active flavor set.

### `KDR_DIRECT_ACCESS`

**Default when unset:** loopback

Where a request that reached this node without a proxy -- carrying neither `X-Forwarded-For` nor `Forwarded` -- may come from and count as direct, which lets it read the `operator` section without a login (its GETs, not its POSTs; issue #1255). `loopback` (the default): from this machine, so a command run on the node over SSH can read it. `network`: from any address, for a node only its own private network can reach, such as one behind an edge. `off`: never, for a machine shared with other users or services. Any proxy in front of the node must set one of the two headers, or every request through it would count as direct. Another value refuses the start.

### `KDR_DISPLAY_TIME_ZONE`

**Default when unset:** unset: the deployment's day start (UTC-8)

The IANA time zone (America/Chicago) the webapp shows moments in for a client that names no zone of its own (issue #1115). Display only: the day such a client's dates fall on is still the deployment's official day start, a fixed UTC-8, which never moves for daylight saving -- so a moment near midnight may be shown on the day before or after the official one. A client naming its own zone reads and shows its days in that zone. An offset or an abbreviation (EST) stops the boot.

### `KDR_ENV`

**Default when unset:** `local`

**Read before deployment flavors resolve**, so a flavor cannot default it.

The environment name -- `local`, `unit`, `prod`. Drives environment-specific behavior (whether the sample app loads, whether a database host is defaulted, and much more). Read before the instance exists, so it is resolved at pre-boot rather than through an instance.

### `KDR_ENV_AUTH_FROM_ADMIN_LOGIN`

**Default when unset:** false

On a public test deployment with **no edge** -- an ephemeral instance, whose database is refreshed or discarded -- a signed-in user is env-authed when their address is on the admin domain (`KDR_ADMIN_EMAIL_DOMAIN`) and they hold `allClients`, read live (issue #1150). Such an address may also sign in, and is mailed its codes for real, whatever `KDR_PUBLIC_SIGN_IN` says. Refused unless `KDR_PUBLIC_TEST_DEPLOYMENT` is on, `KDR_TRUST_ENV_AUTH_HEADER` is off (there is no edge to strip it), and the admin domain is set and is not a consumer mail domain, every address of which would be an administrator's.

### `KDR_FLAVOR_DIR`

**Default when unset:** `flavor-overlays` under the workspace

The flavor directory: where a deployment keeps its own copy for its active deployment flavors, one `<flavor>.md` each in the omnibus fragment format (`# !<fileId>` above the ordinary `# @namespace` / `# +key`). Each is layered over every component's copy for that flavor, key by key, so it names only what it changes. A relative path resolves against the workspace. A missing directory or file is normal.

### `KDR_GOOGLE_CLIENT_ID`

**Default when unset:** unset

The deployment's Google OAuth client id, which turns Google sign-in on: unset (the default) and the feature is neither offered by the auth UI nor accepted by its endpoint. Public by design -- it identifies the application to Google and the browser presents it -- so an environment variable rather than a secret. Also what an incoming ID token's `aud` claim is checked against, which is deliberately why there is no default. Every origin the page is served from must also be registered against it as an Authorized JavaScript origin in Google Cloud Console.

### `KDR_LOAD_SAMPLE`

**Default when unset:** on for `local`/`dev`, off otherwise

Force-loads (`true`) or skips (`false`) the `sample` module's demo file upload/download endpoints, overriding the default (developer environments only).

### `KDR_MAIL_DOMAIN`

**Default when unset:** unset (required when mail can be sent)

The domain mail is sent from, through Mailgun -- e.g. `mg.dynamicruntime.org` (issue #1250). The endpoint (`https://api.mailgun.net/v3/<domain>/messages`) and the from-address (`kdr-automail@<domain>`) are derived from it; the config keys `mailgunUri` and `appFromAddress` override either. Required when the node loads a Mailgun key -- whenever something may be sent for real -- and the boot is refused without it. With no key loaded, as on every test and local run, nothing is sent and it is not needed.

### `KDR_MAIL_TRANSMIT_ADMIN_DOMAIN`

**Default when unset:** false

On a test instance that simulates email, also transmit mail addressed to the admin domain (KDR_ADMIN_EMAIL_DOMAIN or a subdomain) for real, through the provider, when its API key is configured -- the mail is still captured for read-back. For trying real invitations and codes locally. Off by default; has no effect on a non-simulating instance, which transmits everything.

### `KDR_OBFUSCATE_ERRORS`

**Default when unset:** on when `KDR_ENV=prod`, off otherwise

Replaces the message of an error flagged `sensitive` -- one that would reveal, say, whether an account exists -- with a generic sentence before it reaches the client. The real message is still logged. The `obfuscateSensitiveErrors` config option wins over this.

### `KDR_PUBLIC_SIGN_IN`

**Default when unset:** blocked

On a public test deployment, what a caller without env auth may do about signing in (issue #1150): `blocked` (the default) refuses them every sign-in and registration flow; `realMail` lets them sign in as on a real deployment, their mail transmitted for real through the provider and never captured, so nobody can read it back. Real mail is an opt-in, so a deployment never starts mailing the public because an API key is present -- and a public form then mails any address typed into it, which it relies on the code-sending throttles (`AuthRateLimiter`, per source IP and per address) to bound.

### `KDR_PUBLIC_TEST_DEPLOYMENT`

**Default when unset:** false

Makes this test instance a **public test deployment** (issue #1150): one the general public can reach, clearly marked as for testing and development only. It is what permits a test instance in `dev` -- otherwise one refuses to start outside `local`/`unit`. The test surface is already only for an env-authed caller (#1149); under this, env auth is hardened: `KDR_TRUST_ENV_AUTH_HEADER` must be set explicitly (true only behind an edge that strips the header from outside traffic), and env auth is assumed only on a node listening on loopback alone (`KDR_BIND_ADDRESS`). A caller without env auth gets what `KDR_PUBLIC_SIGN_IN` says, and every page and mail says the deployment is for testing.

### `KDR_PUBLIC_URL`

**Default when unset:** unset (derived from the request)

The URL a browser reaches this deployment at, e.g. 'https://app.example.com', used to build the links that mailed invitations carry (issue #751). Unset (the default), a link is built from the request's own scheme and host -- right for a node reached directly, wrong behind a proxy that rewrites them, which is when this must be set. No trailing slash. Its host is also the one every mail names in its closing "Sent from" line (issue #1276); unset, only a test instance names the request's host there, and a real deployment names none.

### `KDR_SESSION_HOURS`

**Default when unset:** 48 (two days)

How long a sign-in lasts, in hours, for a client that sets no lifetime of its own. Held to KDR_SESSION_MAX_HOURS. A value that is not a whole number of at least 1 is ignored.

### `KDR_SESSION_MAX_HOURS`

**Default when unset:** 720 (30 days)

The longest a client's administrator may make its people's sign-ins last, in hours; it also caps KDR_SESSION_HOURS. A value that is not a whole number of at least 1 is ignored.

### `KDR_TAGS`

**Default when unset:** none

This node's capability tags (issue #433), comma-separated -- what surfaces it serves, as opposed to the singular boot role. Role-prefixed like the rest, so an edge reads `KDR_EDGE_TAGS` first. A node carrying no tags is admitted by every declaration that names none.

### `KDR_TEST_INSTANCE`

**Default when unset:** unset (derived)

Forces this to be a test instance, independent of environment -- exposing `forTestingOnly` endpoints and simulating/capturing email by default. Also true implicitly when `KDR_ENV=unit` or `inMemoryOnly` is on. A test instance outside `local`/`unit` refuses to start, so test affordances cannot reach a real deployment.

### `KDR_TRUST_ENV_AUTH_HEADER`

**Default when unset:** on for a test instance, off otherwise

Whether this node believes the `X-Kdr-Env-Email` header an edge server sets on a request it has already authenticated. A node cannot verify that claim -- the real guarantee is that only the edge can reach it, a network property -- so the deployment asserts it here. A node not behind an edge leaves it off. Env auth is a property of the channel, not the user: it grants no role and never stands in for a login. The `trustEnvAuthHeader` config option wins over this.

### `KDR_WORKSPACE_DIR`

**Default when unset:** found by walking up to `settings.gradle.kts`

**Read before deployment flavors resolve**, so a flavor cannot default it.

The workspace directory (the Gradle build root that contains this repo). Read before the instance exists, via `System.getenv`. Normally left unset -- the directory is found by walking up to the nearest `settings.gradle.kts`.

## Database

### `KDR_ALLOW_SCHEMA_DRIFT`

**Default when unset:** off

Boots despite **blocking** schema drift -- a `NOT NULL` column with no default that the database has and the code does not declare (issue #216). Off by default, so such a deployment refuses to start (it cannot write). The escape hatch only downgrades the refusal to a logged error; writes still fail. For an operator mid-migration who has a legitimate reason to start.

### `KDR_DB_HOST`

**Default when unset:** localhost in `local`, else required

PostgreSQL host, with an optional `:port` suffix (else the default port). Defaulted to localhost only in the local environment; other environments must set it.

### `KDR_DB_NAME`

**Default when unset:** `kdr` (in-memory H2: the instance name)

Database name, used by file-backed H2 and PostgreSQL. It also names an in-memory H2 database, which otherwise takes the instance name, so separate instances in one JVM do not share tables; set it to make instances share one.

### `KDR_DB_TYPE`

**Default when unset:** in-memory H2

Selects the database type; ignored when in-memory mode forces in-memory H2.

### `KDR_DB_USER`

**Default when unset:** `kdr`

PostgreSQL username. Postgres only (the H2 variants use a hardcoded user); rarely set.

### `KDR_IN_MEMORY_ONLY`

**Default when unset:** true

Default for `inMemoryOnly` mode (parsed loosely as a boolean). When true, the runtime uses in-memory state and the database type is forced to in-memory H2.

## Logging

### `KDR_LOG_ASYNC`

**Default when unset:** false (sync)

**Read before deployment flavors resolve**, so a flavor cannot default it.

Toggles async delivery of our logs; default sync, for immediate, ordered output.

### `KDR_LOG_LEVEL`

**Default when unset:** `debug`

**Read before deployment flavors resolve**, so a flavor cannot default it.

The log level for our application topics (`trace`/`debug`/`info`/`warn`/`error`).

### `KDR_ROOT_LOG_LEVEL`

**Default when unset:** `info`

**Read before deployment flavors resolve**, so a flavor cannot default it.

The root log level for everything else (third-party log4j2 loggers).

## Node identity

### `KDR_BIND_ADDRESS`

**Default when unset:** every interface

The address the HTTP server listens on (issue #1245). Unset, it listens on every interface. `127.0.0.1` makes it reachable from this machine alone, whatever a firewall says -- what a node behind a proxy on the same machine (Caddy, on a lightweight node) wants, and what lets a public test deployment assume env auth for a request that did not come through the proxy.

### `KDR_HOSTNAME`

**Default when unset:** the OS `HOSTNAME`, else `localhost`

Overrides the node's host name, used in the node label; falls back to the OS `HOSTNAME`.

### `KDR_NODE_IP_ADDRESS`

**Default when unset:** `127.0.0.1`

Overrides the node's IP identity, used in the node label.

### `KDR_PORT`

**Default when unset:** 7070 (or the boot role's default)

Overrides the HTTP port the server binds to. Set this to run a second instance alongside another without a port collision -- usually together with `KDR_IN_MEMORY_ONLY=true`. The one variable a boot role must NOT inherit from the unprefixed name (issue #377): under a role only `KDR_EDGE_PORT` and the role's default are consulted, because two nodes sharing a port collide, quietly.

## Caching

### `KDR_TABLE_CACHE_DISABLED`

**Default when unset:** off (caches on)

Turns every registered table cache off; reads fall back to their SQL path.

### `KDR_TABLE_CACHE_MIN_RECHECK_MS`

**Default when unset:** 30000

Overrides the backstop interval (ms) at which a cache reconsiders changes made outside the application, which nothing announces. Cross-node promptness for an announced change is set by the state-read throttle, not this floor.

## Content

### `KDR_FRAGMENT_CHECK`

**Default when unset:** strict (`warn` in `prod`)

What a fragment problem does at startup: `strict`, `warn` or `off`. Unset means strict everywhere except `prod` -- a developer or test should be stopped by a broken fragment; a production node should not refuse to serve everything else over a defect in one piece of copy.

### `KDR_IMAGE_CHECK`

**Default when unset:** strict (`warn` in `prod`)

What a problem with an image a component ships -- one copy names, or one in a web resources package -- does at startup: a file that is missing, too large, or not an image, a name declared twice or under another owner's root: `strict`, `warn` or `off`. Unset means strict everywhere except `prod`, where the image is left out: copy naming it draws its alt text, and a client's slot shows the deployment's.

### `KDR_SOURCE_REPO_BRANCH`

**Default when unset:** 'main'

The branch that source-repository document links point at (see KDR_SOURCE_REPO_URL). Defaults to 'main'. Has no effect when KDR_SOURCE_REPO_URL is set empty.

### `KDR_SOURCE_REPO_URL`

**Default when unset:** 'https://github.com/sampwhite/KotlinDynamicRuntime'

The web base URL of the source repository that served documents link into, e.g. 'https://github.com/owner/repo'. A served Markdown document's interior links to repo files that are not themselves served in-app are rewritten to '<this>/blob/<branch>/<path>' (issue #492). Unset, it is the upstream repository, since the documents served are the runtime's own (issue #1380); a fork that serves its own copies sets its own. Set empty, nothing is rewritten, and such a link is drawn as its text, since it has nowhere to go from inside the app.

## Gedra

### `KDR_GEDRA_CONFIG_CHECK`

**Default when unset:** strict (`warn` in `prod`)

What a Gedra config problem does at startup: `strict` (refuse to boot), `warn`, or `off`. Unset means strict everywhere except `prod`, which warns.

### `KDR_IMPACT_SCAN_LIMIT`

**Default when unset:** 50000

The most stored rows a publish impact report examines (issue #935). The report runs in memory over the caches, so a client with more rows than this gets a report saying it is too large, which a publish asks to be acknowledged like any finding -- a report covering part of the rows would pass a harmful change. The default is the report runs' (KDR_REPORT_SCAN_LIMIT).

### `KDR_LOAD_STORED_CONFIG`

**Default when unset:** on for a persistent node, off for an in-memory one

Whether a node loads stored client configurations from the database at boot, beside the source-declared ones. Unset, it loads only on a persistent (non-in-memory) node.

### `KDR_REPORT_HISTORY_KEEP`

**Default when unset:** 400

How many days of one report's snapshots are kept per client (issue #1034). Of each past day only the latest snapshot survives, and days beyond this many are deleted as a new snapshot is written, oldest first; today's snapshots are all kept until the day is over, so pressing Snapshot now cannot push the nightly series out. The default keeps over a year.

### `KDR_REPORT_SCAN_LIMIT`

**Default when unset:** 50000

The most forms one report run reads (issue #981). A report runs in memory over the caches, so a client with more forms than this is refused with a message naming this variable rather than given a report that silently covers part of its forms -- a partial report is worse than none. The default is the ceiling the forms listing's in-memory search states (#538).

### `KDR_STORED_CONFIG_CHECK`

**Default when unset:** warn (`strict` in `unit`)

What a problem in a client's stored (database) configuration does when it is loaded: `strict` (refuse), `warn` (log, drop the offending piece, carry on) or `off`. Unset means warn everywhere except `unit`, which is strict. Source-code config is governed by KDR_GEDRA_CONFIG_CHECK.

## Edge

### `KDR_EDGE_UPSTREAM`

**Default when unset:** `http://localhost:7070`

Where an edge forwards application traffic -- every request whose leading path segment is an application context root (`kda`/`cp`/`wa`/`st`) rather than one of the edge's own. Read only by a node in the `edge` role. The `edgeUpstream` config option wins over this.

### `KDR_ENV_AUTH_SESSION_MILLIS`

**Default when unset:** 2 days (12 hours in `prod`)

How long an edge env-auth session cookie lasts, in milliseconds. The `envAuthSessionMillis` config option wins over this.

## Batch jobs

### `KDR_JOB_CONFIG_CHECK`

**Default when unset:** strict, except warn in production

The mode of the boot check on job configuration (`strict`, `warn` or `off`): an override naming a job profile no registered job uses, or a setting no profile has -- both written in code, so either is a typo that would otherwise be silently ignored -- and a schedule a node cannot honor: slots under a minute apart outside a test instance, or slots or a window shorter than the scheduler's tick.

### `KDR_JOB_POOL_THREADS`

**Default when unset:** the number of processors

The size of the node's batch-job thread pool, which every job's CPU-bound tasks share (a job's profile says how many of them it may use at once). Created on the first pooled launch, so a node that runs no jobs starts no threads.

### `KDR_MULTI_NODE_TEST`

**Default when unset:** off

Switches the multi-node test component on (issue #872), as `multiNodeTest.enabled` in a `customConfig` does. Only in development builds: the module is not in the deployable distribution.

## File store

### `KDR_FILE_STORE_CHECK`

**Default when unset:** strict, except warn in production

The mode of the boot check that the file store can serve every bucket a component declared (`strict`, `warn` or `off`): a directory that cannot be created or written to, an S3 bucket that does not exist or cannot be reached. Under `warn` the node starts, and storing or reading a file in that bucket fails when it is tried.

### `KDR_FILE_STORE_DIR`

**Default when unset:** `fileStore` in the workspace directory

The directory a `local` file store keeps its files in, each bucket a directory under it. A relative path is taken from the workspace directory. A unit test that runs on the `local` store must name one: it never defaults into the workspace.

### `KDR_FILE_STORE_TYPE`

**Default when unset:** `memory` when running in memory only (and in a unit test), else `local`

Where files are kept: `memory` (in the JVM, gone when it stops, and bounded by nothing but the heap -- for tests and a throwaway node), `local` (a directory on this machine, see KDR_FILE_STORE_DIR) or `s3` (S3, or a service that speaks its API -- see the KDR_S3_ variables). Every node of a deployment must name the same store, and `local` is one store only where the nodes share the directory, on a filesystem that honors file locks between them.

### `KDR_LOCAL_FILES_DIR`

**Default when unset:** `localFiles` in the workspace directory

Where the local files a file ref may name are kept: each directory directly under it is a **root**, by its name, and a ref names a root and a path under it. A relative path is taken from the workspace directory; an absolute one is taken as written, so the files need not be in the workspace. Nothing creates it: until a deployment puts a root there, there are none. It may not be inside one of the places that hold the deployment's own data, nor contain one. A unit test is given none unless it names one, as an absolute path: it never defaults into the workspace.

### `KDR_LOCAL_FILE_CLIENT_ROOTS`

**Default when unset:** none

The roots of local files that are **open to clients**, by name, separated by commas: `approved,seasonal`. A client's own administrator may name a file in such a root, where otherwise only the server's own code and an administrator who sees every client may name a local file at all. What they name is a ref of its own kind (`clientLocal`), which reads the client's own directory there, `<root>/<clientId>/<path>`, and no other client's. Such a ref is read only where its root is open: set this the same on every node. A name that is malformed, or given twice, refuses the start; a well-formed name that matches no root -- a typo, or a root not made yet -- opens nothing, and is said on the startup line.

### `KDR_LOCAL_FILE_ROOTS`

**Default when unset:** none

Roots of local files placed somewhere other than under KDR_LOCAL_FILES_DIR, as `name=location` separated by commas: `brand=acmeRepo/images,shared=/srv/kdr/imagery`. How a source repository's own artwork is a root without being copied. Each location is resolved as KDR_LOCAL_FILES_DIR is, and held to the same rule about where it may be; a name declared here takes the place of a directory of that name under KDR_LOCAL_FILES_DIR.

### `KDR_S3_BUCKET_PREFIX`

**Default when unset:** empty

What this deployment's S3 bucket names begin with: the bucket the application calls `web-assets` is the S3 bucket `<prefix>web-assets`. S3 bucket names are shared by all its users, so a deployment needs names of its own. The buckets must already exist; the application creates none.

### `KDR_S3_ENDPOINT`

**Default when unset:** unset (AWS itself)

The URL of a service other than AWS that speaks the S3 API, for an `s3` file store. Tried against Linode (Akamai) Object Storage at the endpoint its access key lists, such as `https://us-ord-10.linodeobjects.com` (issue #1271). Addressed by path rather than by a bucket's own hostname, and an upload through it carries no trailing checksum, which the AWS SDK otherwise adds and S3-compatible services need not accept.

### `KDR_S3_REGION`

**Default when unset:** unset; required for `s3`

The region of an `s3` file store's buckets: AWS's (`us-east-1`), or with KDR_S3_ENDPOINT the service's own -- for Linode, the first part of the endpoint's hostname (`us-ord-10`). Credentials are not configured here: they come from the AWS SDK's default chain -- AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY in the environment, a profile in `~/.aws/credentials` (AWS_PROFILE to pick one), or the machine's role -- so they are never in this application's configuration or its secrets file.

## Workflow assistant

### `KDR_ASSISTANT_CONCURRENT_TURNS`

**Default when unset:** 3

The most turns that may run at once in one client, across its administrators and nodes.

### `KDR_ASSISTANT_EFFORT`

**Default when unset:** medium

How hard the model thinks: one of low, medium, high, xhigh, max. Higher costs more per turn.

### `KDR_ASSISTANT_KEY_SECRET`

**Default when unset:** anthropicApiKey

The name of the secret in `private/secrets.properties` holding the Anthropic API key. With no such secret the assistant is unavailable, and the operator's view names the one it looked for. A test's node reads `noAnthropicKeyInUnitTests`, which no secrets file holds, unless the test names another.

### `KDR_ASSISTANT_MAX_STEPS`

**Default when unset:** 20

The most model calls one turn may make (each tool round is one) before it stops and says so.

### `KDR_ASSISTANT_MAX_TOKENS`

**Default when unset:** 16000

The most tokens one model call may write, thinking included; at most 16000.

### `KDR_ASSISTANT_MODEL`

**Default when unset:** claude-sonnet-5-5

The Claude model the assistant asks: one of claude-sonnet-5-5, claude-opus-5-5, claude-fable-5-1, claude-fable-5, claude-mythos-5-1, the models that accept what its request asks for (adaptive thinking with progress updates, and server-side fallbacks). Another refuses the boot.

### `KDR_ASSISTANT_MONTHLY_BUDGET`

**Default when unset:** 10000000

A client's monthly budget in weighted tokens (input 1, cache write 1.25, cache read 0.1, output 5, at Sonnet's prices: 500,000 to the dollar, so the default is about $20), when an operator has set none of its own. A sandbox counts against its parent's.

### `KDR_ASSISTANT_PROVIDER`

**Default when unset:** anthropic

What answers the workflow assistant: `anthropic` (Claude, through the Anthropic API, when the deployment holds a key) or `fake` (a built-in demo that lists the client's workflows with a real tool call and names them, for trying the page locally with no key and no spending). `fake` is refused at boot on a node that is not a test instance.

### `KDR_ASSISTANT_SELF_SERVE`

**Default when unset:** false

Whether the assistant is offered to self-serve clients (issue #1568): `true` lets a self-serve client's administrator switch it on for their client, acknowledging the data notice themselves, with the budget KDR_ASSISTANT_SELF_SERVE_BUDGET sets. Anyone may sign up for one, so it is off unless the deployment says so; a client an operator has set, on or off, stays as the operator set it.

### `KDR_ASSISTANT_SELF_SERVE_BUDGET`

**Default when unset:** 2500000

A self-serve client's monthly budget in weighted tokens, when its administrator switches the assistant on (KDR_ASSISTANT_SELF_SERVE): the default is about $5. An operator may change it after.

### `KDR_ASSISTANT_TURNS_PER_HOUR`

**Default when unset:** 20

The most turns (messages sent to the assistant) one user may start in an hour.
<!-- kdr-generated: end -->

## About this file

**Everything above this section is generated** from the declarations themselves, every one the repository's modules
make, so it cannot disagree with what the runtime reads. Unlike the other references, it is not served on the
**Documents** page: a running node knows only the variables it has read so far, and its operator view,
`GET /operator/env/reference`, lists those with the value each resolved to there. `./gradlew :launch:refDocsDump`
copies it into this file, and the suite fails while the copy is behind the code, so edit the declaration (below),
never the copy.

## Where it comes from

| What | Source |
|---|---|
| A variable's declaration: its name, area, default, and description | [`EnvVarDef.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/context/EnvVarDef.kt), and each `EnvVarDef` beside the code that reads it |
| How a variable is resolved: instance config, the environment, a boot role's name, a flavor | `resolveByName` in [`KdrInstanceConfig.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/context/KdrInstanceConfig.kt) |
| The reference page, and the operator view beside it | [`EnvVarReference.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/operator/EnvVarReference.kt) |
| How every declaration is found | `declaredEnvVars` in [`ReferenceCopies.kt`](../../launch/test/kdn/ReferenceCopies.kt) |
