<!-- kdr-generated: begin -- copied from its generator by `./gradlew :launch:refDocsDump`; edit the generator, not this -->
# Roles and access

Two things decide what a caller may do. Their **roles** decide which endpoints they may call, and their **read scope**
decides whose data those endpoints show them. Both are worked out on every request from the roles as they are now, so
a role given or taken away takes effect at the caller's next request, with no need to sign in again.

This page lists the roles, the sections of the API and who may call each, and how far each caller reads. Then it
covers what changes in a sandbox, on a client's own copy of an endpoint, on a test instance, and for an API key, and
how to ask a node why a caller is refused. It is made from the role constants and the section rules the runtime
enforces, so it says exactly what the runtime does.

## Roles

A user holds roles of two kinds.

- **A level**, one of `user`, `operator`, `admin`, from least to most. A higher level reaches whatever a
  lower one does, so an `admin` may call what an `operator` may. A user holds one level: setting another
  replaces it. `user` is the floor, kept whatever level is set.
- **A role beside the levels**, held outright. A deployment may add roles of its own; they match only themselves, and
  never stand in for a level.

| Role | Kind | What it is |
|---|---|---|
| `user` | Level 1 of 3 | The floor every account stands on: a user without it cannot sign in. Reaches the sections any signed-in caller may. |
| `operator` | Level 2 of 3 | Runs the deployment. Paired with `allClients`, it reaches the deployment-wide `operator` section; without it, only `clientOperator`, confined to the caller's own client. |
| `admin` | Level 3 of 3 | Administers a client: its users and their roles, its configuration, and its API keys, in `clientAdmin`. Paired with `allClients`, every client, in `admin`. Ranks above `operator`, so it reaches what an operator does. |
| `allClients` | Capability | A capability, not a level: it widens a level's reach from one client to every client, and confers nothing on its own. An `admin` or `operator` without it is confined to their own client. |
| `editor` | Role, implied by `admin` | Sees every user and every form of its client and edits forms, administering nothing: no user edits, no user creation, no client configuration. Reaches `clientDirectory`. Implied by `admin`, so an administrator holds it without its being granted. |

**Who may grant what.** An administrator sets a user's roles on the Users page, which calls
`POST /clientAdmin/user/setRoles` (or `POST /admin/user/setRoles` for a user of any client). A role list must keep
`user`. Nobody may grant `allClients` without holding it, nor `operator`, whose section needs
`allClients` too. An administrator may not take away their own `admin`, nor disable their own account,
so the last one cannot lock everyone out. A deployment's first administrator is made outside the API: by signing in
with an address on the domain `KDR_ADMIN_EMAIL_DOMAIN` names, or by the `GrantRole` command.

## Sections

The first segment of an endpoint's path is its **section**: `clientAdmin` for `/clientAdmin/user/setRoles`. The
section decides who may call it, and there is no rule for one endpoint alone. [Endpoints](endpoints.md) lists every
endpoint under its section. A page has a section too, the first segment after its context root, and the same rules
apply to it: `/cp/portal` is in `portal`. A page whose section is not listed here is open to anyone.

| Section | Who may call it | What it holds |
|---|---|---|
| `health` | Anyone | The node's health and identity, for a load balancer or a monitor. |
| `schema` | Anyone | The endpoint catalog, which lists what the caller may call with each endpoint's schema, and the schema for schema. |
| `portal` | Anyone | A page, not endpoints: under the content root (`/cp/portal`), a form for exploring and calling each endpoint the caller may call. |
| `auth` | Anyone | Signing in and registering, invitations, who the caller is, leaving a sandbox, and exchanging an API key for an access token. |
| `app` | Anyone | The web application's configuration, and the switch a session turns its env auth off, on, or to debug with. |
| `fixture` | Anyone | What tests, and developers trying something by hand, call: every endpoint here is test only, or comes from a component loaded only in a developer environment. |
| `demo` | Anyone | Demonstrations of a capability for a person to see, such as uploading a file. |
| `home` | Anyone | The shell's configuration: its layout, links, and menu. |
| `logout` | Anyone | Signing out. |
| `user` | Signed in | The signed-in user's own account: their API keys, their other users and clients and switching between them, joining or creating a client, and opening a sandbox. |
| `profile` | Signed in | The signed-in user's profile: their name and password. |
| `gedra` | Signed in | Forms and the data in them. How far a caller reaches here is their read scope, not the section's rule. |
| `operator` | `operator` or above, with `allClients`; or a direct read | Running the deployment: system information, boot checks, caches, jobs, environment variables, the file store, signing keys, and the assistant's switch for each client. |
| `clientOperator` | `operator` or above | A client's own operations, confined to the caller's client: today, the facts the client knows. |
| `node` | `admin` or above, with `allClients` | Node identity and statistics, beside `admin`; no endpoint is served under it today. |
| `admin` | `admin` or above, with `allClients` | Administration across every client: any client's definition and configuration, its users, and creating or retiring clients. |
| `clientAdmin` | `admin` or above | Administration of the caller's own client: its users, their roles and API keys, its configuration, design, sandbox, reports, and assistant. |
| `clientDirectory` | `editor` (or `admin`) | The read-only user directory: the users of the caller's client, listed, searched, and read. |

A section marked "or a direct read" also admits a GET that reaches the node without passing through a proxy, signed in
or not: one made on the node itself, such as by a command run there.

The administrative sections pair a level with a reach:

| | Every client (with `allClients`) | The caller's own client |
|---|---|---|
| `admin` | `admin` | `clientAdmin` |
| `operator` | `operator` | `clientOperator` |

An administrator of every client may also call `clientAdmin`, where their reach is not confined.

**A refused call.** A caller signed in is answered 403, and one who is not, 401. Either way, the message names the
role or capability that is missing: "Request requires the 'allClients' role." The endpoint catalog,
`GET /schema/endpoints`, lists only what the caller may call, so an endpoint withheld from them is absent from it
rather than marked.

## Whose data a caller reads

Within a section that admits them, what a caller reads, and whose users an administrator administers, is their **read
scope**. It is narrow unless something widens it.

| Caller | Reads |
|---|---|
| Any signed-in user | Their own rows. |
| `editor` | Every row of their client, its users included, administering none of them. |
| `admin` | Every row of their client, and administers its users. |
| `admin` in the `public` client | Their own rows, as any user does, and administers only their own users. |
| `admin` with `allClients` | Every client's rows and users. |

A client may group its users in **organizations**. An editor or administrator with a primary organization reads that
organization's rows and the client's rows that belong to no organization, not the whole client. `allClients` is
not narrowed by an organization.

## A client's own copy of an endpoint

Some endpoints have a copy for each client, at a path naming the client: `/gedra/<client>/formDoc` for
`/gedra/formDoc`. [Endpoints](endpoints.md) marks them "per client". A client's copy answers with that client's schema,
and is open to that client's users and to a holder of `allClients`. To anyone else it does not exist: it is answered
as a path with no endpoint, so it does not reveal which clients exist.

A copy also confines. It refuses a row of another client, and a listing through it lists only its client's rows, even
for a holder of `allClients`, whose reach on the shared endpoint is every client.

## Sandboxes

A client may have a **sandbox**, `<client>:sandbox`, where changes are tried before anyone else sees them. It has users
of its own, and authority over it flows from the client to the sandbox, never back.

- **Who may open it:** an administrator of the client, or one with `allClients`, and never from inside a sandbox.
  Opening it moves the session to the person's own user in the sandbox, made on first use.
- **A user opened that way** acts with exactly the roles and organization of the user it was opened from, read at every
  request. A role taken away outside is gone inside at the next request. If that user is disabled, or no longer stands
  for the client (as a user of it, or an administrator of every client), the sandbox user acts with no roles at all.
- **A user made in the sandbox** acts with the roles its row holds, except as the rules below say. `admin` is
  never granted to one: setting it is refused.

| Rule | What it says |
|---|---|
| `sandboxAdmin` | In a sandbox, admin takes effect only for an identity that is an administrator in the parent, or an allClients administrator. |
| `sandboxAllClients` | In a sandbox, allClients is held only through the user the sandbox was opened from. |
| `sandboxStanding` | This sandbox user acts for somebody outside it who has no enabled user of the parent they have claimed, nor an enabled allClients administrator. |
| `sandboxLevel` | In a sandbox, a level beside admin on a user nobody recorded opening takes effect only while the person's user outside holds it. |

## Test instances and env auth

**Env auth** says how a request arrived, not who is acting: an edge server signed the person in to the environment and
vouches for them in a header the node trusts (`KDR_TRUST_ENV_AUTH_HEADER`), or, on a developer's own test node, it
is assumed (`KDR_ASSUME_ENV_AUTH`). It grants no role, and a session may switch it off to see what everyone else
sees.

**A test instance** (`KDR_TEST_INSTANCE`) refuses to start outside the `local` and `unit` environments,
except as a public test deployment (`KDR_PUBLIC_TEST_DEPLOYMENT`), which hardens env auth. A caller with
**test access**, on a test instance and with env auth, has:

- the endpoints [Endpoints](endpoints.md) marks "test only", among them the `fixture` section's. To a caller without
  test access they do not exist, as on a real deployment;
- the diagnostic `_debug` tags, such as `explainAccess` below, the debug pages, and error detail on screen.

On any deployment, a session with env auth switched to debug has the diagnostic tags, debug pages, and error detail
too, but not the test only endpoints.

## API keys and access tokens

An API key acts as its user, with that user's roles and read scope as they are at each request: a role taken away
from the user is gone from the key at once. An access token exchanged for a key carries the key's access. Either of
them:

- with `read` access, makes GET requests and nothing else, save exchanging a key for a token;
- with `full` access, may do whatever its user may, except call an endpoint [Endpoints](endpoints.md) marks
  "session only": managing keys, signing in or out, switching users or clients, and changing a password.

How to make, use, and exchange a key is in [API keys and access tokens](../api-keys-and-tokens.md). The workflow
assistant calls the API for the user it works for in the same way, and reaches only the endpoints its work needs.

## Asking why a caller is refused

Add `_debug=explainAccess` to a call of the endpoint catalog, `GET /schema/endpoints` or `GET /schema/endpoint`, as the
caller. It answers on a test instance for a caller with test access, or for a session in env debug; anywhere else the
report is simply left out. The report is under `_meta.accessExplained`:

| Key | What it holds |
|---|---|
| `actingRoles` | The roles the caller acts with now, which the catalog compared. |
| `withheld` | Each section withheld from the caller: its `section`, the `requiredRole` and `requiredCapability` it asks for, and its endpoints. |
| `rolesWithheld` | For a sandbox user, each role its row holds that does not take effect: the `role`, and the `rule` above that withholds it. |
| `sandboxActsAs` | For a sandbox user opened from another user, the user it acts as, and that user's client and organization. |
<!-- kdr-generated: end -->

## About this file

**Everything above this section is generated.** A running server makes it from the role constants and the section rules
its request gate enforces, so it cannot disagree with the gate. Read it on the server's **Documents** page, under
**Reference**, then **Roles and access**, where anyone may open it without signing in. On the public reference site it
is at [dynamicruntime.org/wa/docs/roles-and-access](https://dynamicruntime.org/wa/docs/roles-and-access).
`./gradlew :launch:refDocsDump` copies it into this file, and the suite fails while the copy is behind the code, so edit
the generator (below), never the copy.

On a running server, a link to this file opens the reference itself.

## Where it comes from

| What | Source |
|---|---|
| The roles, what each is, and the order of the levels | `ROLE` and `RoleLadder` in [`RoleConstants.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/http/request/RoleConstants.kt) |
| The sections, who may call each, and what each holds | `SectionAccess` in [`SectionRules.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/http/request/SectionRules.kt) |
| Whose data a caller reads | `ReadScopeRules` in [`ReadScopeRules.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/user/ReadScopeRules.kt) |
| What a user acts with in a sandbox | `SandboxAccess` in [`SandboxAccess.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/user/SandboxAccess.kt) |
| The reference page, made from these | [`RolesReference.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/user/RolesReference.kt) |
