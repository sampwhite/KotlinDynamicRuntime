# Sandboxes

A **sandbox** is a second client linked to a client. It runs the client's *latest* configuration, drafts included,
against users and data of its own. It is where a client's administrator sees an unpublished change working before
publishing it to the client's people. This document says what a sandbox is, why it is built as a client, what it runs,
who may enter it and with what authority, and how it changes the way edits and publishing behave.

**Status: built**, under the tracking issue #925:

| Phase | Issue |
|---|---|
| Sandbox client ids, `<parent>:sandbox` | #927 |
| Loading and reloading a sandbox beside its parent | #928 |
| Who may open a sandbox, and as whom | #929 |
| Edits from inside a sandbox act on the parent, as drafts | #930 |
| Entering, marking, and leaving the sandbox in the webapp | #931 |
| Managing a client's sandbox from the Clients page | #932 |
| The impact report a publish is judged by | #935 |
| Source-code overlays for a sandbox | #940 |

Later work tightened the access rules (#1194, #1196, #1197, #1199, #1200), made a session change without a page reload
(#1015), and made being in a sandbox unmistakable (#1049). What is not built is listed at the end.

"Shadow Sandbox" is the internal name, used in the issues. The product only ever says "sandbox".

## Why a sandbox is a client

Everything derived from configuration is held per client: the schema variant, the endpoint copies, the cfacts, the
copy and menu overlays, the workflow registries, the type cache, and the marker that keeps nodes in step. A sandbox
needs every one of them, built from different configuration than its parent's. Making it a client of its own gives it
all of them with no service gaining a second key.

It also keeps one rule true: **stored data never has two configurations competing over it.** The sandbox's forms are
the sandbox's, read only under the sandbox's configuration, and the client's are read only under the client's. A
change is tried against data made for trying it.

Authority flows **down, never up**. A client's administrator may open its sandbox and act there. Nothing done inside
the sandbox gives anybody standing in the client, except the edits to configuration described below, which are made
*as* the parent's administrator and land as the parent's drafts.

## Asking for one

A client asks for a sandbox through the `sandbox` field of its definition (`ClientDef.sandbox`, default `false`):

- **At creation, the creator chooses.** A client created by delegation takes the creator's choice. A self-serve client
  always has one, so it runs only what it has published (see
  [`client-definition.md`](client-definition.md), issues #1112 and #1113).
- **Afterward, through the sandbox's own management.** The client's administrator adds or removes the sandbox from the
  Clients page (`POST /clientAdmin/client/sandbox`). An administrator of every client may set it on any client.
- **Changing it publishes, never drafts.** A change to whether a sandbox exists cannot wait in the sandbox it creates
  or removes. It is refused while the client has unpublished changes, so that nothing is published by accident.

The sample's `acme` client has a sandbox (#994), and so does the client the impact demo simulation provisions.

## The sandbox client

**Its id is the parent's, with `:sandbox` appended:** `acme:sandbox`. The colon is reserved for this form. No author
can write such an id: a client definition whose id holds a colon is refused, a stored configuration filed under a
sandbox is refused, and the create endpoints refuse one. A client whose id holds a colon has no sandbox, so there is no
sandbox of a sandbox, and the global client has none. The form `<parent>:<role>` leaves room for another kind of linked
client one day.

The colon was checked everywhere a client id travels (`SandboxClient.kt`):

| Where | What happens to the colon |
|---|---|
| A gedra id, `gd.fd.acme:sandbox.e2026…` | kept. The id's separators are `.` and `~`, so it still parses. `encodeURIComponent` escapes the colon in a URL, and readers decode it. |
| An endpoint copy, `/gedra/acme:sandbox/formDoc` | kept. A colon is legal in a path, and an endpoint key is never split on one. |
| The webapp's hash, `c=` | encoded and decoded |
| A schema namespace | never reached. A sandbox declares into its parent's namespace, which matters because an OpenAPI key may not hold a colon. |
| A file path | never reached. No file path holds a client id today; a file-store key layout that ever does must encode it, since Windows forbids the colon. |

**Its definition is derived from the parent's** (`SandboxConfigs.deriveDef`), never written:

| Field | In the sandbox |
|---|---|
| `clientId` | `<parent>:sandbox` |
| `name` | the parent's name, with " (sandbox)" |
| `usageType` | `dev` |
| `audience` | `internal` |
| `domainPrefix`, `customDomain` | none. A sandbox is never routed to by domain. |
| `preload`, `staticConfig`, `sandbox` | `false` |
| everything else | the parent's: included traits, user labels, test features, environments, customer, and time zone |

A sandbox is on its parent's plan, with its forms and users counted apart from the parent's. It belongs to its
parent's customer but is not listed as one of the customer's clients.

## What a sandbox runs

**Three layers, in order** (`SandboxConfigs.configsFor`):

1. the parent's source configuration;
2. the sandbox's own source overlays (#940);
3. the parent's **latest** stored revisions, whatever tier the parent itself runs.

Each layer is rebound to the sandbox: its configurations' ids and their copy and menu overlays name the sandbox, and
its client definition is the derived one. Its namespace stays the parent's. Of two layers' copy or menu overlays, the
later wins. Traits and workflows are held to their first definition, as everywhere else.

**A client with a sandbox runs only what it has published** (#930). If its people were served its latest configuration
too, a draft would reach them as soon as it was written, and the sandbox would preview nothing. So asking for a sandbox
makes the client published-only. The rule reads the client's *published* definition, not a draft of it, and the boot
applies the same rule, so the tier survives a restart.

**A source overlay is a per-client feature flag.** A source configuration filed under `<parent>:sandbox` is loaded into
the sandbox only. Code can therefore try a change on one client's sandbox and be promoted by moving it into the
parent's source. An overlay for a client that asks for no sandbox is reported on that client and not loaded, and so is
an overlay that repeats what the parent's source already declares. Where the parent's configuration is static on this
node, its sandbox runs source and overlays only, with no stored layer.

**Loading and reloading:**

- **Every node builds the sandbox,** in-memory nodes included, beside its parent and from the same rows. A
  published-only parent's drafts are kept at the load, since they are its sandbox's.
- **Reloading the parent reloads the sandbox** under the same lock, and the result names both.
- **A sandbox can be reloaded alone,** as when a peer node catches up on the sandbox's own sync marker.
- **When the parent stops asking for a sandbox,** the sandbox's reload withdraws everything it had.

**A sandbox is never a base.** A client may not extend a sandbox, and one that tries is dropped. The sandbox of a client
that *does* extend a template keeps `extendsFromClientId` and is cloned against its own configuration, the parent's
drafts, rather than carrying the parent's published clone (see
[`client-definition.md`](client-definition.md#inheritance-between-clients)).

**A sandbox is present only while its parent is present and asks for one** (`ClientCheck.relatedProblem`). A parent
that is dropped by a check, retired, or not enabled in this environment takes its sandbox with it. A sandbox cannot be
retired on its own. Retiring a client with a sandbox is published at once, since otherwise it would be saved as a
draft.

## Users and data

**A sandbox's data is its own.** Its forms, users, and stored gedras carry `acme:sandbox` in their ids, and are kept
apart from the parent's by the same client boundary that keeps any two clients apart. Removing a sandbox keeps its
users and data, and they return if the sandbox is added again. Nothing yet clears them.

**Who may open a sandbox** (`SandboxAccess.mayOpen`): an administrator of the client, or an administrator of every
client. Nobody opens a sandbox from inside a sandbox.

| Endpoint | What it does |
|---|---|
| `POST /user/self/openSandbox` | Enters the sandbox of the caller's client, or of the named `client`. Finds or creates the caller's own user there, matched by identity, persona, and suffix, records who opened it, and completes as a fresh session. |
| `POST /auth/self/leaveSandbox` | Switches back to a user of the caller's own identity outside. It needs no role, so a session whose standing has gone can still leave (#1197). |

**What a sandbox user acts with** is worked out again at every request (`SandboxAccess.standingOf`):

- **A user somebody opened acts exactly as the opener does:** the opener's roles, levels, capabilities, and, if the
  opener is the parent's own, organization (#1196). If the opener is disabled, or is no longer the parent's
  administrator or an administrator of every client, the user acts with **nothing** until they leave.
- **A sandbox's own users act with their own roles, with limits.** `admin` takes effect only while the same identity
  is an enabled administrator of the parent, or an administrator of every client, and then in that administrator's
  organization. `allClients` is never held in a sandbox. A level beside `admin` takes effect only if the identity also
  holds it outside (#1194, #1199). Only a claimed user counts (#1200).

Why a role is withheld is a named reason (`SandboxAccess.Withheld`, an enum), and `_debug=explainAccess` reports
it. The user console refuses to grant `admin`, or a level beside it, inside a sandbox, and does not let an opened
user's roles or organization be edited. A user who would act with nothing is passed over at sign-in, as a disabled user is.

## Editing from inside the sandbox

**An edit to configuration made in the sandbox acts on the parent, as a draft** (#930, `SandboxEdits`). The edit is
made in the parent's name, by the parent's administrator the sandbox user acts as, and is refused if there is none. The
parent is then reloaded, and so the sandbox shows the change. This covers the `/clientAdmin/config/…` endpoints
(bundles, write, patch, publish, revert, impact, traits, reload, and the tier), and the edits a page makes for the
client: copy, menu, the definition's presentation, file refs, and Design View's shared edits.

**For a client with a sandbox, every such edit is a draft, from either side.** An edit made on the client's own page
is a draft too, and the sandbox is where it shows. Each result says whether it was saved live or as a draft (`mode`).
A caller may say which it expects, and a save that would do otherwise is a 409 (`saveNotAsExpected`): one expected as a
draft that would be live, one that names a sandbox the client no longer has, or one for a client that asks for a
sandbox this node does not carry.

Some things are deliberately not drafted:

- **The sandbox's own definition** is derived, so it cannot be edited.
- **Web assets** (the favicon and brand mark) are not configuration. A sandbox shows its parent's and may change none.
  [File refs](file-refs.md) are configuration, and are drafted like the rest.
- **Design View** on a client with a sandbox refuses the client's own page and points to the sandbox, where a change
  can be seen.

## Publishing is judged against the client

A write is judged with the latest revisions, which is what the sandbox runs, so the sandbox's existing problems are
the drafts' existing problems and do not refuse an unrelated write. A **publish** changes what the client's own people
are served, so it is judged against the client alone. A problem the sandbox already has refuses the publish that would
bring it to them. The trial is described in
[`config-and-data.md`](gedra/config-and-data.md#what-is-checked-and-what-a-failure-does), and how it treats a file
that has gone missing in [`file-refs.md`](file-refs.md#refs-into-the-file-store).

**The impact report** (#935) exists because of the sandbox. A change tried in the sandbox meets only the sandbox's data,
so it can look fine there and still break the client's stored forms when published. A publish therefore judges the
candidate against the *client's* rows, and asked from inside a sandbox, the report is about the parent's data.

## Elsewhere

- **Reports.** A sandbox holds its parent's reports, under generated type names of its own. The nightly history job
  skips sandboxes. A snapshot can still be taken by hand. See [`reporting.md`](reporting.md).
- **File refs.** A sandbox's files are its parent's, kept under the parent, and it previews refs the parent has only
  drafted. See [`file-refs.md`](file-refs.md#inheritance-sandboxes-and-what-is-stored).
- **Time zone.** A sandbox's is its parent's.
- **The Clients page and the overview** list a sandbox beside its parent (`sandboxOf`, `hasSandbox`), show "the
  configuration it runs" as the parent's, and offer publishing "from its sandbox, after previewing it there", with the
  impact check.
- **The webapp shell** carries `canOpenSandbox` and, inside a sandbox, `sandboxOf`, `sandboxOfName`, and
  `sandboxStranded`. It tints the page with a band across the top, labels the client chip "Acme Sandbox", offers a way
  back, and names the client in a delete confirmation ("acme's sandbox", against "acme (live data)") (#1049). The pure
  helpers are in `SandboxShell.kt`.

## Tests

The behavior above is pinned by these specs, in `base/kdn`:

| Spec | What it covers |
|---|---|
| `SandboxClientTest` | loading and reloading beside the parent, and placing a `TestUser` in a sandbox |
| `SandboxBootAndSyncTest` | the source layer, a persistent boot, and a peer catching up by the sandbox's marker |
| `SandboxBootTierTest` | published-only surviving a restart |
| `SandboxAccessTest` | who may open, and what a sandbox user acts with |
| `SandboxEditsTest` | edits from inside as the parent's drafts |
| `SandboxManageTest` | adding and removing a sandbox |
| `SandboxOverlayTest` | source overlays |

The `kdr-test-clients` skill shows how a test gives its own client a sandbox and acts inside it.

## What is not built

- **Clearing a sandbox's data.** A removed sandbox keeps its users and forms, and nothing clears them or resets a
  sandbox to empty. #925 deferred this. It is not yet recorded in
  [`docs/development/deferred-work.md`](../development/deferred-work.md).
- **Another kind of linked client.** The `<parent>:<role>` form allows for one, such as a client for per-client time
  travel, but none exists.
- **Open issues** that touch sandboxes: tests for access in a few remaining cases (#1201), drafts and the impact check
  in the traits editor (#1117), and which client a report runs against in the sandbox (#1119).
