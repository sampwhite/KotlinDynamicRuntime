# File refs

A client's **file refs** are its named files: an image its copy shows by name, with the bytes kept in the file store
or on the node's own disk. This document says what a ref is, where its bytes may be, who may name them, how a
configuration holding refs is judged, and how a ref reaches a browser.

**Status: built** (issues #1153, #1154, #1164, #1169, #1170, #1171, #1176, #1180, #1186, #1188, #1190, #1191, #1192).
Images are the only kind of file a ref may name today. What is not built is listed at the end, and each item has an
entry in [`docs/development/deferred-work.md`](../development/deferred-work.md) under the condition that should reopen
it.

The rules here grew out of the configuration rules in
[`docs/design/gedra/config-and-data.md`](gedra/config-and-data.md), where these sections first lived (issue #1294).
That document still holds the rules every slot follows. A ref is held to them, and to the extra rules below, because it
is the first slot whose entries name something outside the configuration.

## What a ref is, and what it is not

A ref is an entry in the `kdr:fileRefDef` slot of a client's configuration. The entry names the file by an id of the
client's choosing, and says where the bytes are by its `source`:

| `source` | Where the bytes are | Fields |
|---|---|---|
| `store` | the deployment's file store, by the hash of the bytes | `refId`, `hash`, `description` |
| `local` | a file on the node's disk, under a root the deployment declares | `refId`, `root`, `path`, `description` |
| `clientLocal` | the client's own directory, in a root the deployment opened to clients | `refId`, `root`, `path`, `description` |

Because a ref is configuration, it is drafted, previewed in the sandbox, and published with the rest of the client's
configuration. That is what separates it from the two things it is easily confused with:

- **A client's web assets** (issue #1130) are its favicon and brand mark: two fixed slots, uploaded from the Clients
  page and kept in the same bucket, but not configuration. They have no draft, and a sandbox shows its parent's. Refs
  and web assets share the image check (`WebAssetCheck`) and the serving code (`HashedFileServing`).
- **`GedraDataType.fileRef`** is a kind of *data* gedra: a claim on a file held as data. It has no write path yet. The
  two share the word and nothing else.

The limits are in `FREF` (`FileRefConstants.kt`):

| Limit | Value |
|---|---|
| Length of a ref id | 64 characters. An id follows the rule for a client's own names: no dot, slash, or colon. |
| Length of a description | 500 characters |
| Length of a local path | 512 characters, so that a path always fits a file-store key |
| Characters in a root's name | letters, digits, `_`, and `-` |
| Refs a client is served | 200 |
| Size of a file | 2 MB |

Two further rules:

- **A ref belongs to a client.** A global configuration that declares one has a load problem, since there is no client
  whose file it would be.
- **Refs are an app node's.** `FileRefService` runs only on app nodes. An edge has none.

## How a ref is shown

**Copy names a ref, never its bytes** (issue #1186). A client's Markdown copy shows an image by its ref id:

```markdown
![Our logo](ref:logo)
```

The copy says *which* image, so replacing the image needs no edit to the copy. A ref the page cannot resolve is drawn as
its alt text, never as an empty image. Mail never shows a ref; it always shows the alt text.

**Copy naming a ref the client lacks is a problem of the copy.** It is judged when the client loads, at a reload, and
in a write's trial (`reportCopyNamingNoRef`). The copy is kept, and the image is drawn as its alt text. A ref that
exists but whose file is missing is a problem of the ref instead, described below.

**The address carries the hash:** `/<staticRoot>/<appId>/ref/<refId>:<hash>`, where the static root is `/st` unless
the deployment sets another. Two things make the address work:

- **The shell config tells the page which hash each ref has.** The `state.fileRefs` field maps each ref id to
  `<refId>:<hash>`. A user in a sandbox is given the sandbox's refs, and a ref whose file is missing is left out. The
  map is remembered for 30 seconds and forgotten at a reload, and a failure to build it never breaks the shell.
- **The server serves the caller's client's ref, whatever the address says.** The `appId` is not read, and no
  sign-in is needed. The cache header depends on whether the bytes match the address:

  | The bytes served | Cache header |
  |---|---|
  | match the hash in the address | `public, max-age=31536000, immutable` |
  | do not match it (the file changed since the address was made) | `no-store` |
  | are missing | a `no-store` 404, as plain text |

  Every answer also carries `X-Content-Type-Options: nosniff`. An SVG is served with a content security policy that
  allows no script, so an SVG can never run code in the page.

The administrator's download (`GET /clientAdmin/client/files/download`) is a separate address. It is cached
privately, it is sent as an attachment named `<refId>.<ext>` rather than under the uploaded file's name, and with
`served=true` it shows what the client's people are served, rather than the draft (issue #1188). The copy editor uses
it to preview another client's refs, and offers to insert a ref's image into the copy.

## Refs into the file store

`kdr:fileRefDef` is the first slot whose entries name something that is not in the configuration: a
client's **file ref** is a named file, and its entry says where the bytes are by its `source`. Most are in the
**file store** -- `{ refId, source: "store", hash, description? }`, the SHA-256 of the file's bytes, kept in
bucket `web-assets` at `clients/<client>/refs/<hash>` -- and those are what the points below describe. A ref may
instead name a **local file**, which the section after covers.

- **The hash is the pointer.** The key is derived from it and never stored, so an entry names exactly one sequence
  of bytes, an object is written once and never rewritten, and a draft cannot change what a published revision
  names. Removing a ref leaves its object, which older revisions still name.
- **What a file is -- its type, its size, its name -- is not in the entry.** It is the file store's own record,
  written by the upload that checked the bytes. An entry can be typed through the configuration endpoints, and the
  content type a file is served under must not come from something that can be typed. Serving checks, on every
  read, that the file is the one the ref names and a kind a ref may be (`StoreFileSource.read`).
- **Drafted and published like any other configuration.** `FileRefService` builds a registry per client from the
  configurations the collector holds for it, so the tiers and the sandbox need no rule of their own: a sandbox
  previews a ref its parent has only drafted, and the parent serves the one it published. This is the difference
  from a client's web assets (#1130), which are not configuration and have neither.
- **Judged in a fixed order.** A load and a write's trial read a client's configurations in different orders, so
  the registry orders them itself before it judges them -- the stored ones, then the source ones, each by id: of
  two configurations declaring one ref id, the first in that order keeps it, in both. A check whose answer
  depended on the order read would be a new problem to every trial.
- **Only stored configuration may hold a ref into the file store.** A hash names bytes in one deployment's file
  store, and source configuration runs on every machine: such a ref declared in source is a source problem, which
  refuses the boot outside production. (A ref declared in source names a local file.)
- **A load asks the store once per distinct file**, and goes on the answer for ten minutes (an object is not
  rewritten, so the answer changes only if one is deleted). A ref whose bytes are missing is a reported problem that
  costs only the ref: it is kept, listed as missing, and answered as not found.
- **Whether a file is there is asked again at each write** (`GedraConfigTrial.fileRefBaseline`). A write's trial
  subtracts the problems the client already has, and every other check reads only configuration, so what the last
  load recorded stays true. A file does not: one that went missing since the load would be a problem "the write
  introduces" to every later write of the client, including the one that takes the ref away. So the refs the node
  runs for the client, and for its sandbox, are judged again as the trial is, and a write is refused only for a
  file its own ref names.
- **Drafts nothing runs are judged as they were** (issue #1171). A client that runs only published configuration
  *without* a sandbox has latest revisions no node loads, so no load records what they already have. For such a
  client a draft write's baseline also holds a trial of the stored set as it stood before the write -- each written
  config back at its prior revision, a new one left out -- so a draft ref whose file has gone, or any other problem
  a draft already had, does not refuse a later draft write that does not bring it; one that does is refused as ever.
- **A sandbox's problems forgive a write, not a publish.** A write is judged with the latest revisions, which the
  sandbox runs, so what the sandbox already has the drafts already have. A publish changes what the client's own
  people are served, and is judged against the client alone: a draft ref whose file has gone is refused there,
  since publishing it is what would bring the problem to them.
- **A reload never refuses for a file that went missing under a ref the node already serves**
  (`reportConfigProblem(aroseWhileRunning = …)`). Where problems are strict, anything else a load finds refuses
  it; but this one no write could have been refused for, and the write that led to the reload is stored by then.
  It is reported and recorded, and the ref kept. A boot, and a ref a reload *brings*, are held as strictly as
  ever.
- **Uploading a file again puts it back.** An upload reads the file it would store before deciding it is already
  there, so one whose bytes are gone or damaged is written again.
- **A client is served at most 200 refs.** Holding more is one problem of the client's, with the first 200 served
  and the rest left out, which is what bounds a load. A write that would take a client over is refused by its
  trial -- whichever configuration, and whether it is an upload, a typed entry or an import -- and an upload says
  so before it stores the file. A client already over can still take refs away.
- **Nothing removes a file.** A replaced or removed ref's object stays for the revisions that still name it, and
  nothing yet bounds how many a client leaves behind: nothing sweeps away what no revision names. (What a write
  that stopped part-way leaves is another matter, which the operator's `CheckFileStore` script finds, #1147.)
- **An export or import does not carry files.** A configuration imported where the bytes are not -- another
  deployment's store, or **another client of this one**, since a file is kept under the client whose configuration
  names it -- is refused, naming each ref whose file is not there.

## Refs to local files: an image where it already is

The second `source`. `{ refId, source: "local", root, path, description? }` names a file on the node's own disk,
under a **root** the deployment declared -- `fileRef("logo") { local("brand", "logo.svg") }` in source
configuration. No hash is authored: it is the hash of the file as it is read. Where roots are, and what may not be
one, is the deployment's to say and is written up in `examples/custom-config.md`; the rules themselves are
`LocalFileRoots`.

- **Which source may be written where.**

  | | source configuration | stored, by the server's own code or an `allClients` administrator | stored, by a client's own administrator |
  |---|---|---|---|
  | `store` (a hash) | refused: a problem at the load | yes | yes |
  | `local` (a root and a path) | yes | yes | **no** -- 403 |
  | `clientLocal` (a root and a path, in the client's own directory) | yes | yes | only into a root open to clients -- else 403 |

  A path in configuration is a string naming a file on the server, and stored configuration is written through
  the API by a client's own people. So `GedraConfigService` refuses a write that **adds or changes** a local ref
  from anyone but the server's own code or an administrator who sees every client, judged against the revision
  read under the write's lock -- a bundle write, a patch and an import alike. "The server's own code" is a write
  with no signed-in caller (a script, a job) and a write that says its content is the code's whoever started it
  (`ConfigWrite.authoredInCode`, which `Simulations.provisionConfig` sets: a simulation is started by a signed-in
  caller, and what it writes is the simulation's). **A client's own administrator's** write may still *carry* a
  local ref the revision already holds, so their edit of the same configuration is not refused for something they
  did not write; it may reword the ref's description, which names no file; and it may leave the ref out, or
  upload over it. An edit of theirs that is refused and undone puts back what was there
  (`ConfigPatches.edit(restoring = …)`).
- **A client's own ref is the third source** (issue #1164). `{ refId, source: "clientLocal", root, path }` reads
  `<root>/<owner>/<path>`: the directory, in a root the deployment opened to clients
  (`KDR_LOCAL_FILE_CLIENT_ROOTS`), of the client whose ref it is. It is the one local ref a client's own
  administrator may write (`GedraConfigService.mayNameLocalFile`, asked of the root's *name* and never of the
  disk, so a root that is not open is refused the same whether or not it exists).
  - **Why a source of its own.** What a ref reaches is then said by the ref, and not by how a node is configured.
    A `local` ref names `<root>/<path>` whatever is opened, so opening a root changes nothing an operator wrote.
    A `clientLocal` ref names the client's directory, and where its root is not open -- the deployment closed
    it, one node was started without the variable, the release was rolled back to one that does not read the
    source -- it names **nothing**, and is reported and answered as not found. It never becomes the other kind.
    Had division been a property of the root, every one of those would have turned a tenant's ref into one that
    reads from the root's top.
  - A write that turns one kind into the other is a re-pointing (`FileRefDef.pointer` carries the source), and
    is held to the rule for the kind it writes.
  - The owner is `configOwnerOf(client)`: a sandbox's is its parent, and for a ref a client inherits from a
    template it is the inheriting client -- so a template's `clientLocal` ref is a file each for the template
    and for every client built on it, with the template's file standing in for a client that has none (issue
    #1180, under [Inheritance](#inheritance-sandboxes-and-what-is-stored)). The entry holds the root and the path
    as written and never the client's segment: copied to another client, it names that client's own file.
  - `POST /clientAdmin/client/files/local` is the call that names one, landing as an upload does; it refuses a
    root that is not open, and a file that is not there, before it writes anything. `GET …/files/roots` lists
    the open roots by name, to the administrator of a client.
- **A client's page is where its administrator works with them** (issue #1176), through the endpoints listed under
  [Where an edit lands](#where-an-edit-lands).
- **What is stored overrides what is shipped.** A stored ref takes the place of a source ref of the same id, with
  no problem said -- as stored copy takes the place of the source's. So an upload replaces a shipped image, and
  removing the upload reveals it again. Any *other* second declaration of an id is still a problem.
- **The entry is held to its source** by the slot gate: a `store` ref has a hash and no root or path, a `local`
  one a root and a plain path and no hash. An entry with both would be read by one and look like the other.
- **The bytes are hashed every time they are served** (`LocalFileSource`). Remembering a hash by a file's size and
  date could give the permanent cache header to bytes the address does not name. So a file edited on disk is a new
  address with no reload, an old address gets the current bytes under `no-store`, and nodes holding different
  files answer each other's addresses the same way -- a shared cache only ever keeps the exact bytes an address
  names. A steady state needs every node to hold the same files.
- **A file that is missing, unreadable or not an image** is a `no-store` 404 when asked for and a configuration
  problem when its client loads, which keeps the ref. The problem says one thing whatever the reason, since what a
  parser says of a file that is not an image can repeat what is in it; the reason is in the node's log.
- **A listing reads each local file** to say its hash, so its cost is the files'; the limit of 200 refs a client
  and 2 MB a file is what bounds it. A load and a write's trial do not: they ask only whether a file can be
  served, and that is remembered for a file that has not changed (where it is, its size, its date), so a save
  costs a `stat` a ref and not a read. Serving remembers nothing.
- **Roll it out everywhere first**, as with the slot itself: a node on the release before reads no `local` ref
  (it leaves one out, as a source it does not read), and its upload over one writes an entry this release drops.

## Where an edit lands

The Files section of a client's page and the endpoints behind it write a ref through the client's stored
configuration (`ClientFileRefEdit`). An edit:

- **is refused, if at all, before any bytes are stored.** An empty file, a file over 2 MB, a file the image check
  refuses, and an upload that would take the client over 200 refs are all refused first.
- **lands in the configuration already holding the ref,** or else in the editors' own configuration, `copy`. So an
  upload is published together with the copy that names it.
- **is a draft if the client has a sandbox, and live if it does not.** Unlike a web asset, a ref may be uploaded from
  inside the sandbox. The caller may say which it expects (`mode`), and a save that would do otherwise is a 409
  (issues #1191, #1192).
- **records the uploader's display name** beside the file, which the listing shows.

The endpoints are all under `/clientAdmin/client/files`. Each takes an optional `client`: the caller's own, or any
client for an administrator of every client. There is no `/admin` equivalent, though a ref can also be typed into a
configuration through the generic configuration endpoints.

| Path | Method | What it does |
|---|---|---|
| `/clientAdmin/client/files` | GET | Lists the refs, with their source, file, whether stored configuration holds them, whether they may be removed, who set them and when, and whether the file is missing or comes from a template. For a client with a sandbox it lists the sandbox's refs, with rows for refs a draft removed. |
| `…/upload` | POST, multipart | Stores a file and writes a `store` ref (`refId`, `file`, `description`). |
| `…/remove` | POST | Removes a ref. |
| `…/describe` | POST | Rewords a ref's description. Refused if no stored configuration holds the ref. |
| `…/restore` | POST | Puts back the published entry of a ref a draft removed (issue #1188). |
| `…/local` | POST | Names a file in the client's own directory of an open root, as a `clientLocal` ref. |
| `…/roots` | GET | Lists the roots open to clients, by name. |
| `…/takes` | GET | Says what a ref's file may be: its largest size, the accepted types, the ref limit, and the open roots. |
| `…/download` | GET | Downloads a ref's file, or the one the client's people are served (`served=true`). |
| `…/download/meta` | GET | The same file's record, without its bytes (issue #1131). |

The accepted kinds of image are PNG, JPEG, WebP, ICO, and SVG. The bytes decide: a file whose bytes do not match its
declared type is refused, not corrected. An SVG must be well-formed XML with an `svg` root, read by a parser that
fetches nothing. It may hold no `script` element, no `on…` attribute, and no `javascript:` value.

The Files section offers each of these as row actions: Replace, Reword, Remove, and Put back for a ref a draft removed.
It can add a ref by upload, or by root and path where a root is open. It can find, mark, and order refs, shows 20 rows
before "Show all", and copies a ref's address (issue #1190). Where the client's configuration is static on this node,
the section is read-only.

## Inheritance, sandboxes, and what is stored

**A template passes on only its source refs** (issue #1170). A client built on a template takes clones of the
template's *source* configurations, so it inherits the template's `local` and `clientLocal` refs. Since a `store` ref
cannot be in source, uploads to a template are never inherited. A ref the child's own source declares replaces the
template's of the same id. A ref the child stores overrides the template's instead, so a stored override that cannot
be served leaves the template's ref in place.

**An inherited `clientLocal` ref falls back to the template's file** (issue #1180). The client's own directory is read
first, and the template's only if the client's file is *absent*. A file that is present but cannot be served is the
client's problem; it is not covered by the template's.

**A sandbox's refs are its parent's files.** A sandbox runs its parent's latest configuration, so it previews a ref the
parent has only drafted. Its bytes are kept under the parent (`configOwnerOf`), and a `clientLocal` ref in a sandbox
reads the parent's directory. See [`sandboxes.md`](sandboxes.md).

**What is stored overrides what is shipped,** as described above: of a stored ref and a source ref with the same id,
the stored one is served, and no problem is said.

## Storage and operations

**Bytes in the file store** are kept in bucket `web-assets` under `clients/<owner>/refs/<hash>`, with a
`<key>.meta.json` record beside each file holding its type, size, hash, upload time, name, and uploader. The owner is
the client, or a sandbox's parent. Every access goes through `FileStoreService.forOwner`, the one place a per-client
store would be routed if one is ever built.

The file store's provider is chosen by `KDR_FILE_STORE_TYPE`:

| Provider | Where the bytes are |
|---|---|
| `memory` | in memory, for tests |
| `local` | a directory, `KDR_FILE_STORE_DIR` |
| `s3` | an S3-compatible bucket named `<KDR_S3_BUCKET_PREFIX>web-assets`, which must already exist (issue #1129). See [`docs/deploy/lightweight-node.md`](../deploy/lightweight-node.md). |

**Local roots** are configured by three variables, described in
[`examples/custom-config.md`](../../examples/custom-config.md):

| Variable | Meaning |
|---|---|
| `KDR_LOCAL_FILES_DIR` | A directory each of whose subdirectories is a root. It defaults to `localFiles` in the workspace. |
| `KDR_LOCAL_FILE_ROOTS` | `name=location,...`, naming roots directly. A name here overrides a subdirectory of the same name. |
| `KDR_LOCAL_FILE_CLIENT_ROOTS` | The names of the roots open to clients. A malformed or repeated name refuses the boot. A name that is not a root opens nothing, and the startup line says so. |

The node logs its resolved roots at startup. A root may not be one of the deployment's own data places
(`LocalFileRoots.reservedPlaces`).

**Checking the store** (issue #1147). A write that stops part-way can leave a file without its record, or the reverse.
The operator's `CheckFileStore` script reports what it finds, and repairs it with `--repair`:

```bash
kdr-run com.dynamicruntime.script.CheckFileStoreKt web-assets --min-age-minutes 60
```

It boots only the file store, never the database, and refuses the memory store. It exits 0 when the store is clean or
repaired, 2 when something is still found, and 1 for a usage or store error.

## What is not built

Each of these is in [`docs/development/deferred-work.md`](../development/deferred-work.md) under its trigger, and the
issues named were closed as not planned.

- **Carrying files with an export or import** (issue #1153). Today an import that names a file not in the target's
  store is refused.
- **A file kind other than an image,** PDF first (issue #1166).
- **Moving a client's images into the file store in bulk,** including promoting local refs to store refs (issue #1165).
- **Naming a ref as the favicon or brand mark,** so branding is drafted in the sandbox (issue #1187).
- **A client's files in a store of its own,** rather than the deployment's (issues #1283, #1284, #1285).
- **Local files at scale:** remembering a served file's kind by its hash (issue #1172), browsing a client's own
  directory of an open root (issue #1177), showing the roots on the operator report (issue #1178), and fencing data
  where configuration moves it (issue #1167).
- **Credentials from AWS STS** for an S3 store (issue #1145).
- **Sweeping files no revision names.** Nothing removes a file today.

**Beside refs, images a component ships** (issue #1339). Copy names one as `![A diagram](img:sample:siteVisitFlow)`:
an image declared by a component (`ComponentDefinition.images`), read from its classpath at boot, checked as a ref's
file is (`WebAssetCheck`, at most a ref's size), and served the same way (`HashedFileServing`, at
`/<staticRoot>/<appId>/img/<name:hash>`). It is the same for every client, so it is a separate namespace from refs:
a client wanting another image changes its copy to name a ref of its own.
