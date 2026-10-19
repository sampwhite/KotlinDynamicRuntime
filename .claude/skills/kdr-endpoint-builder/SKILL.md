---
name: kdr-endpoint-builder
description: Author HTTP endpoints in KotlinDynamicRuntime using the endpoint builders — generalEndpoint/itemEndpoint/listEndpoint/fileUploadEndpoint/fileDownloadEndpoint inside schemaModule, the results/item/items response envelopes, protocol fields, and the handler lambda. Use when adding or reviewing endpoints in this codebase.
---

# Authoring endpoints (endpoint builders)

The builders live in `base/common`, package `com.dynamicruntime.common.endpoint`; the wire vocabulary they use
(`EI`, `EP`, `HttpMethod`, `EndpointKind`) is in **`base/kernel`**, under that same package name, so the
frontend shares it. Endpoints are built **immediately** (the full output schema is realized when the builder
runs), and the code to run is a **plain trailing lambda** (no MVC-style indirection). Builds on the schema
layer — see the `kdr-schema-builder` skill.

## The DSL

Declare types and endpoints together for a namespace with `schemaModule`. In a component, the namespace sits
under the component's owner root -- core's modules are `kdr.<area>` (`kdr.home`, `kdr.job`) -- and a module
extending another owner's root names it: `schemaModule(cxt, "kdr.extra", contributesTo = OWNR.kdrRoot) { ... }`
(issue #950). A type name is declared once across every component; a second declaration fails the boot. The
example below uses a bare `users` for brevity:

```kotlin
val module = schemaModule(cxt, "users") {
    type("UserQuery") { type = SCT.kObject; property("namePrefix", "Filter by name prefix") }
    type("User") { type = SCT.kObject; property("id", "User id") { type = SCT.integer }
                   property("name", "Full name") }

    // General endpoint -> data under `results` (always a map object). Often POST.
    generalEndpoint("/user/rename", "Rename a user.", HttpMethod.POST,
        outputRef = "User", inputRef = "UserQuery") { cxt, request ->
        // return the results map
    }

    // GET one resource -> data under `item`.
    itemEndpoint("/user/get", "Fetch one user by id.", HttpMethod.GET, outputRef = "User",
        inputFields = { field("id", "The user's id", required = true) { type = SCT.integer } }) { cxt, request ->
        // return the item (or null)
    }

    // List -> payload under `items`; a `limit` input field is appended.
    listEndpoint("/users", "List users.", outputRef = "User", inputRef = "UserQuery",
        hasMore = true) { cxt, request ->
        // return the items list
    }
}
// module.defs      -> the $defs types keyed by qualified name
// module.endpoints -> List<KdrEndpoint>
```

`schemaModule(cxt, ns) { … }` returns `SchModule(defs, endpoints)`.
`SchModuleBuilder` extends `SchTypesBuilder`, so all the `type(...)`/`property(...)`
DSL is available alongside the endpoint methods.

**A `description` is mandatory and positional** — second, right after the path. Endpoints are the documented
API, so there is no unlabelled endpoint.

**Paths are matched exactly** (`path:method`, `KdrEndpoint.collationKey`). There is **no path-parameter
extraction**: `/user/{id}` would be a literal path, not a template. An id travels in the query string or the
body, as in the `/user/get` example above.

A module may also register an **options provider** — the callback behind a property's `optionsSource(id)`,
which produces a choice list per caller when the catalog is rendered (issue #413):

```kotlin
optionsProvider(CLD.clientOptions, storedConfigMayName = true) { c, _ ->
    namableClients(c).map { SchOption(it.clientId, clientLabel(it.clientId, it.name)) }
}
```

Declared in the same block as the schema that names the id, so a rename that misses one end fails the boot
rather than emptying a dropdown. It is `optionsProvider` here and `optionsSource` inside a property block
because one *registers* an id and the other *consumes* one. See `kdr-schema-builder` for what a sourced list
means for validation.

**`storedConfigMayName`** (issue #1193) says whether a client's *stored* schema may name the source as well. Ids
are global, so without it a client's administrator could put any source on a field of their own, and the catalog
would resolve it for everyone shown that client's forms. It is **off by default**: leave it off unless the
callback answers **per caller**, as `clientOptions` does (it lists only the clients this caller may name). A
source scoped by its own endpoint's gate -- the customer list, the job types -- stays off. Source-declared
schema may name any source either way.

## The kinds (differ by where the payload sits)

Every JSON output also carries `requestUri` (String) and `duration` (number, ms).

- **`generalEndpoint`** → result under **`results`** (a map object).
- **`itemEndpoint`** → single resource under **`item`**; also takes `clientShaped`.
- **`listEndpoint`** → payload list under **`items`**, with `numItems`; options `hasMore`,
  `hasNumAvailable`, `noLimit`, `clientShaped`, `cursorPaged`. Method **defaults to `GET`**, and note the parameter order
  differs: `(path, description, outputRef, method = GET, …)` against general/item's
  `(path, description, method, outputRef, …)`. See *List paging* below for `numAvailable` (on by default when
  there is a `limit`) and how a handler reports the total.
- **`fileUploadEndpoint`** / **`fileDownloadEndpoint`** → see *Files* below.

## Input is FLAT

An endpoint's input is a flat set of top-level fields, so the flat HTTP input — query params and/or the body —
validates directly with no re-grouping. Declared one of two mutually exclusive ways, or neither for a
no-parameter endpoint:

- **`inputRef`** — a named type whose top-level properties become the fields.
- **`inputFields`** — declared inline. `field(name, description, required = false) { … }` mirrors
  `property(...)`: description mandatory, type defaults to string unless the block sets a `type`/`$ref`.

`clientShaped = true` gives the endpoint a **per-client copy** at a path naming the client —
`/clientOperator/cfacts` alongside `/clientOperator/<client>/cfacts` — for every client that varies what the
endpoint answers with. Set it when the *answer* differs per client and the endpoint is outside the `gedra`
section,
whose endpoints are copied without asking. The handler is the same object on both surfaces and reads
`cxt.client`, which is the caller's own on the shared path and the path's on a copy.

A list endpoint appends a **`limit`** field (default 100) as a plain sibling, unless `noLimit`. The resolved
input type is always closed to undeclared properties (`additionalProperties = false`); off-contract keys stay
exempt -- a `_` key, and a `$` key JSON Schema does not reserve. A reserved one (`$comment`, `$ref`) is an
ordinary key, refused like any other.

## Catalog metadata: `publicApi` and `tags` (issues #433, #489)

Every builder takes two optional catalog attributes. Both are **advertisement, not access** — they change
what `/schema/endpoints` *lists* and grant nothing; the section gate is still the only thing that decides who
may call an endpoint.

- **`publicApi = true`** marks the endpoint as part of the documented, supported API. The catalog is
  filterable by it, and a caller **without env auth** (an ordinary production user) is served *only* the
  `publicApi` set (plus, for a client's administrator, the endpoints generated for that client -- see below) — so a client-facing endpoint should carry it and an internal one must not. A published mark
  is only allowed in the user-facing sections (`user`/`profile`/`gedra`/`auth`); `RequestService` refuses to
  boot if one sits in a privileged/internal section (`admin`/`operator`/`node`/…), since that would publish
  the very surface the audit exists to hide.
- **`tags = setOf(ETAG.internal)`** attaches free-form tags, filterable in the catalog by **any-of** (OR). The
  shared vocabulary is the `ETAG` object (`base/kernel`, `endpoint/EndpointConstants.kt`): `internal`
  (ops/introspection — health, system info, cache state) and `frontend` (consumed by a UI widget-group — the
  UI-config endpoints). The set is open; a client or a later endpoint may coin its own. `publicApi` is
  **not** a tag — it is its own boolean axis, the one the env-auth restriction keys on.

A `clientShaped` per-client copy **inherits** both, so a published client-dynamic endpoint stays published on
its `/gedra/<client>/…` copies.

**Query-verb gotcha.** On a GET or DELETE the input arrives as query-string *text*, and the schema layer only
coerces numeric, boolean and date-format types by default (`allowCoerce`, see `kdr-schema-builder`). So any
other typed field on such an endpoint — an `array`, an `object` — needs `allowCoerce = true` set on it, or
`?ids=a,b` fails validation as a `wrongType`. It fails **safe** (a refused call, and a unit test through
`TestHttpClient` catches it, since that stringifies args too), but it fails. Set it on the field:

```kotlin
field("ids", "…") { type = SCT.array; allowCoerce = true; items { type = SCT.string } }   // GET/DELETE only
```

Whether the *builder* should default this on by verb, or coercion should instead happen at the dispatch
boundary (which already knows a query param is a string) — rather than being hand-set per field — is an open
design question (issue #335 review), deliberately left until the pattern or the frontend-shares-the-schema
constraint forces the call. Until then it is per-field, above.

## Files

An upload's request is `multipart/form-data`; a download's response **is** the file, with no envelope. Both are
`EndpointKind.file` — `kind` tells a client how to deal with an endpoint, and the answer for both is "this one
speaks files, not JSON".

```kotlin
fileUploadEndpoint("/file/upload", "Upload a file.", outputRef = "FileInfo",
    inputFields = { field("file", "The file to upload.", required = true) { binaryContent() } }) { cxt, request ->
    val content = request["file"] as? ContentData ?: throw KdrException.mkInput("No file supplied.")
    // ... store it; return metadata, which travels under `results` like a general endpoint's
}

fileDownloadEndpoint("/file/download", "Download a file by id.",
    inputFields = { field("id", "Id of the file.", required = true) }) { cxt, request ->
    contentData   // returning a ContentData makes the executor send it AS the response body
}
```

- A `binaryContent()` field's value is a **`ContentData`**, not a string (see `kdr-schema-builder`).
- A download's handler returns a `ContentData`; the executor sends it instead of building an envelope. Its
  output schema is OpenAPI's `{"type": "string", "format": "binary"}`, so the catalog says "this returns a
  file" and the display engine offers a download rather than parsing bytes as JSON.
- **Never let an uploaded filename choose where a file is kept** — it is attacker-supplied. Compose the key
  yourself, from ids you already hold, and keep the name as metadata (the file store does that for you).
- **Keep the file in the file store**, `FileStoreService` (`com.dynamicruntime.common.filestore`), which runs on a
  directory, S3 or memory by configuration (`KDR_FILE_STORE_TYPE`), so your code never knows which:
  - **A client's file goes through `forOwner(cxt, owner)`** (issue #1282): a view with the same calls, over the
    store that client's files are kept in -- a deployment may declare several (`KDR_FILE_STORES`), and a client is
    assigned one. Its keys are under the client's folder, `clients/<owner>/` (`FNAME.clientKeyPrefix(owner)`), and
    the view takes no others; the service's own calls are the default store's and refuse a key in any client's
    folder, so a client's files are never split between stores. A file that is no client's (the sample's uploads)
    uses the service's calls directly.
  - Your component **declares the bucket** it keeps files in, once, in `addSchema`:
    `collector.addFileBucket(FileBucketDef("client-files", "What is kept there."))`. A bucket is the store's own
    unit of addressing on every provider (lowercase letters, digits, hyphens), and the boot checks each declared
    one can be served. A bucket nobody declared is refused, and so is a name declared twice: `web-assets` is the
    runtime's own (a client's favicon and brand mark, and the files its configuration names), so pick another.
  - `FileStoreService.get(cxt).put(cxt, bucket, key, content, extra)` stores a `ContentData` and answers its
    `FileMeta` — type, size, SHA-256, the uploaded name, who and when, your `extra` — which is also written
    **beside the file** (`<key>.meta.json`). A key is slash-separated segments of letters, digits, `.`, `_`, `-`.
  - `get(cxt, bucket, key)` answers a `StoredFile` (bytes and `FileMeta`; `toContentData()` is what a download
    handler returns) or null. `meta(...)` and `list(cxt, bucket, keyPrefix, limit)` answer the metadata **without
    fetching the file**. `delete(...)` says whether there was one.
  - A listing is for **one folder of files**: it answers at most `limit` (1000 unless you say) and is *refused*
    when the prefix takes in more, rather than handing back the first few as though they were all.
  - **Storing at a key again replaces the file without taking it away**: a reader gets the old file or the new
    one, never neither, and a replace that fails leaves the old one. `extra` comes back as its JSON form.
  - The store decides nothing about **who** may read a file: the endpoint that composes the key is the one that
    has checked the caller. And nothing secret goes in `extra`, which is readable by whoever may read the file.
  - `FileMeta.client` is the client your context is **bound to** (`cxt.client`), as a row's owner is: a job
    storing a file for a client does it in a context bound to that client.

```kotlin
// In your component's addSchema: the bucket, declared once.
collector.addFileBucket(FileBucketDef("client-files", "Files a client's people upload."))

// In an upload handler -- `file` is the upload's ContentData; acme's files, in the store acme's are kept in.
val files = FileStoreService.get(cxt).forOwner(cxt, "acme")
val meta = files.put(cxt, "client-files", "clients/acme/logo", file, extra = mapOf("purpose" to "brand"))

// In a download handler: the file as the response, or a 404.
files.get(cxt, "client-files", "clients/acme/logo")?.toContentData()
    ?: throw KdrException("No such file.", code = EXC.notFound)

// What a file is, without fetching it: one file's, or a folder's -- acme's whole folder, with no prefix.
files.meta(cxt, "client-files", "clients/acme/logo")
files.list(cxt, "client-files", limit = 100)
files.delete(cxt, "client-files", "clients/acme/logo")
```

**Beside a download, offer its meta-only read** (issue #1131): `fileMetaEndpoint`, from
`com.dynamicruntime.common.filestore`, at the download's path with `/meta` after it, with the same inputs and in the
same section -- who may read what a file is, is who may download it. It answers the file's `FileMeta` under
`item` (a GET, `EndpointKind.item`), turns a handler's null into a 404, and declares the `FileMeta` type in the
module itself, so the answer is never described by nothing (a module may declare it as well; it is one type).
There is deliberately no endpoint reading metadata by bucket and key: the store has no access rules, so the
handler composes the key from what it has checked, as the download's does. **Answer what the download would
find**: read the record now, and refuse it where the download would refuse the file, so the two never disagree
about whether there is one.

```kotlin
fileDownloadEndpoint("/file/download", "Download a file by id.",
    inputFields = { field("id", "Id of the file.", required = true) }) { c, request ->
    FileStoreService.get(c).forOwner(c, "acme").get(c, "client-files", "clients/acme/${request["id"]}")?.toContentData()
        ?: throw KdrException("No such file.", code = EXC.notFound)
}

fileMetaEndpoint("/file/download/meta", "What a file is, without the file.",
    inputFields = { field("id", "Id of the file.", required = true) }) { c, request ->
    FileStoreService.get(c).forOwner(c, "acme").meta(c, "client-files", "clients/acme/${request["id"]}")  // null: a 404
}
```

A handler that can say *why* there is no file throws its own 404 instead, as the download beside it does. Both
client surfaces have one: `UADEP.clientWebDownloadMeta` and `UADEP.clientFilesDownloadMeta`.

**The worked example** is a client's web assets (`webasset/ClientWebEndpoints.kt`, issue #1130): an upload
that checks the file is what it says it is before storing it (`WebAssetCheck` -- an upload's content type is the
uploader's claim), a listing that answers the store's metadata and no content, and a download that sets its own
response headers through `c.request?.webRequest`. The same images are served to browsers by a `ContentServer`
under the static root (`ClientWebAssetService.serve`), which is where a file goes out when it is content rather
than an API answer. **How long an answer may be kept is `ContentResources`' to say**, not the endpoint's:
`cacheHeaderFor(matched)` for an address anyone may fetch, `privateCacheHeaderFor(matched)` with `Vary: Cookie`
for one behind a sign-in, and `noStore` on every miss. **Serving a stored file by its content is
`HashedFileServing`'s** (`addressOf`, `send`, `downloadHeaders`): a client's file refs (`fileref/FileRefService.kt`,
issue #1153) are the second server on it, and a third should be too rather than answer those questions again.

## Signatures

- `HttpMethod` — enum `GET` / `POST` / `PUT` (kernel).
- `EndpointKind` — enum `general` / `item` / `list` / `file` (kernel).
- `KdrEndpointHandler = (cxt: KdrCxt, request: Map<String, Any?>) -> Any?` — returns the core payload
  (the `results` map / the `item` / the `items` list / a `ContentData`). Captured verbatim; no name-based
  indirection.
- `KdrEndpoint(path, method, kind, namespace, description, inputFields, inputTypeRef, includeLimit,
  outputSchema, handler, …, validateOutput)` — the output schema is a built JSON-schema **map** with `$ref`s into
  the module's `$defs`. The *input* is not realized here (a type ref cannot be flattened until its target is
  bound); it is resolved on demand by `resolveEndpointInputType` against the compiled types.
- **Output validation.** A response is checked against `outputSchema` when the `validateResponseSchema` config
  flag is on -- which it is in tests (`mkTestBootCxt`) and nowhere else -- or, in every environment, when the
  endpoint itself sets `validateOutput = true`. A mismatch is a 500 naming the endpoint and each failure's path
  and message. Only a JSON envelope is checked; a file download has none. The
  opt-in is for an endpoint whose schema is generated from data and so *is* the contract; it is not a builder
  parameter, and is set only where a `KdrEndpoint` is constructed directly.
- **Endpoints made from data, after boot.** An endpoint that does not exist until configuration says so is
  constructed directly and handed, with the types it uses, to `SchemaService.publishGenerated` as a client's
  `GeneratedSurface`. `gedra/report/ReportTypedEndpoints.kt` is the worked example: one endpoint pair per client and
  report, its output type generated from the report, `validateOutput` on. A generated endpoint is the one thing
  the catalog shows a caller **without env auth** beyond the `publicApi` set (issue #1128): it is listed to a
  caller who oversees its client (`AdminRules.oversees`, the rule its handler refuses by through
  `overseenClient`), since its catalog entry is where its contract lives and its section may not be published.
  Nothing an endpoint declares opts it in. The publication refuses what the boot would have refused of a
  declared endpoint (a section with no rules, an address already taken) and a few things
  only a generator can do: types that refer outside their own surface, and the keywords the boot judges
  (`g-visibleWhen`, `g-layout`, `g-errors`, `g-optionsSource`). A generator fed by client configuration asks
  `SchemaService.trialGenerated` in the configuration's trial, so a write whose surface would be refused is
  refused as a write; and it publishes before it swaps its own state, so a refusal leaves the two agreeing.
- Protocol keys are constants in `object EP` (kernel): `EP.results`, `EP.item`, `EP.items`, `EP.limit`,
  `EP.numItems`, `EP.hasMore`, `EP.numAvailable`, `EP.requestUri`, `EP.duration`; the error keys `EP.status`,
  `EP.errorCode`, `EP.errorMessage`, `EP.extraData` (see *How one runs*); and the off-contract `EP.debug`
  (`_debug`) / `EP.meta` (`_meta`). `defaultListLimit = 100`.

## How one runs

Built endpoints are collected by `SchemaCollector` (each component's `addSchema`), compiled by
`SchemaService` into a `KdrSchemaStore` keyed by `collationKey`, and dispatched by `RequestService`:
input is coerced+validated against the resolved input type (a failure is a 400), the handler runs, and its
return is wrapped in the envelope and sent — unless it already sent a response, or returned a `ContentData`.

A handler faults with `KdrException`, whose HTTP `code` carries (`EXC.notFound` → 404, `mkInput` → 400). The
non-2xx body is a standardized envelope (issue #103), built by `RequestHandler.errorEnvelope`:

- **`status`** — the HTTP code (the exception's `code`); a number, *not* `errorCode`. This is the field that
  named the HTTP code before #103.
- **`errorCode`** — the *logical* code a client branches on (e.g. a parser's `MarkdownError`), promoted to the
  top level from the exception's `extraData` under `KdrException.errorCodeKey`. **Absent** when there is none —
  most errors have no logical code.
- **`errorMessage`** — the message: rendered from fragment copy when the exception carries a `KdrMsg`
  (`KdrException.mkMsg`, issue #108), otherwise the raw `fullMessage()` (the cause chain). A `sensitive` error
  is replaced by a generic sentence where the deployment obfuscates (`KDR_OBFUSCATE_ERRORS`, on in prod); other
  5xx bodies are not yet redacted on the wire (`deferred-work.md`).
- **`errorFromFragment`** — whether `errorMessage` is fragment copy rather than a raw message. Always present.
- **`requestUri`** — as on a success response.
- **`extraData`** — whatever remains of the exception's bag (e.g. `offset` / `line` / `lineCol`), **nested**
  under its own key so it can never shadow a protocol field. Absent when empty.

## Request context

`cxt.request` (`KdrRequest?`) carries the original request data deep in the stack; `cxt.userProfile` carries
identity + `roles`. `cxt.request.responseMeta` is a mutable map a handler can add to; if non-empty it travels
on the response under the off-contract `_meta` key.

## List paging: `numAvailable` and `ListPage` (issue #499)

A list endpoint reports **`numAvailable`** — the total across the whole scoped set, of which the returned page
is a trimmed part — **by default whenever it has a `limit`** (`hasNumAvailable` resolves to `!noLimit`). A
handler that pages a source it cannot count passes `hasNumAvailable = false`; a `noLimit` endpoint reports
nothing extra, since there is nothing to trim by.

Two ways a handler supplies it, and **usually you write neither**:

- **Return the whole scoped set as a plain `List`.** The executor trims it to `limit`, and fills `numItems`
  from the page, `numAvailable` from the untrimmed size, and `hasMore` (when declared) from whether it
  trimmed. This is the common case and needs no handler code — the list you return is the total.
- **Return a `ListPage(items, numAvailable, hasMore)`** when the handler paged *itself* (e.g. a `LIMIT`/`OFFSET`
  SQL query, or the table cache): the page's own values are authoritative and the executor passes them through
  verbatim. Reach for this when materializing the whole set to count it is the wrong cost; otherwise prefer the
  plain list.

## Cursor paging: `after` in, `next` out (issue #976)

A listing somebody walks to the end -- a report, an export, anything a script pages through -- declares
`cursorPaged = true`. The framework then adds an **`after`** input beside `limit`, declares an optional **`next`**
in the output, and declares `hasMore`. The caller sends each page's `next` back as `after` and stops when a page
has none.

```kotlin
listEndpoint("/thing/list", "Every thing, in id order.", outputRef = "Thing", cursorPaged = true) { _, request ->
    cursorPage(request, queryId, sortedThings, { it.id }, naturalOrder(), CursorKeys.string) { it.toJsonMap() }
}
```

- **Return `cursorPage(request, queryId, sorted, keyOf, cmp, codec) { item }`.** It reads `after` and `limit` from
  the request, cuts the page, builds the `next`, and renders only the page's items -- so a listing that is cheap
  to order and costly to render pays for one page. A plain `List` from a cursor-paged handler is a fault: the
  executor can trim a list but cannot say where the next page starts.
- **`cursorPageOf(...) { page -> items }`** is the same with the page rendered **together** -- for a listing whose
  rendering is a batch read (a page of ids, then their rows, states and owners in one read each; the report run,
  issue #981). It may answer fewer items than the page, leaving out one gone since the ordering; `next` is the
  page's last key either way.
- **`queryId`** is whatever makes the query the same query -- the listing, and any input that changes the set or
  its order. A cursor from a different query is a **400**, as is a malformed one, or one whose key is not this
  listing's shape. Never the first page: that would restart a caller's walk without telling them.
- **`codec`** says how the sort key travels in the token: `CursorKeys.string` for an id, or a
  `CursorKeyCodec(toValues, fromValues)` of your own. The key travels as JSON, which does not keep every type -- a
  whole-number `Double` comes back a `Long`, a date as text -- so `fromValues` puts the types back, and answers
  null for values that are not a key of this listing.
- **A page starts after the cursor's key, not after an item** (`cursorSlice`, kernel): the first `limit` items
  whose key is *greater*. So a walk resumes correctly even when the item the last page ended on is gone. A `limit`
  below 1 is a 400, and a key is never null.
- **The order must be total and its key must never change for an item.** An id qualifies. A last-updated date or a
  count does not: an edit moves the item across the cursor, and the walk skips it or returns it twice. That is the
  whole reason to page by cursor, so an endpoint ordered by something mutable should stay on `offset`.
- `cursorPaged` cannot be combined with `noLimit`, and the endpoint must not declare an `after` field of its own --
  inline or in its named input type; either fails the boot.
  A listing whose input names come from configuration (the forms listing's trait search) has to reserve `after`
  before turning cursor-paged.

## List summary: facts about the whole set (issue #791)

Some pages need a fact about **everything** the query could return, not just the page on screen -- the forms
listing's workflow column is drawn only when *some* visible form has a workflow, and every row needs labels its
own data only names by id. A list endpoint declares such a fact with `summaryRef`, the type of an object
delivered under **`summary`**, beside `items`:

```kotlin
listEndpoint(GEP.formDocs, "…", outputRef = docType, hasMore = true, summaryRef = GEP.formDocsSummary) { c, req ->
    ListPage(items, numAvailable, hasMore, summary = if (wanted) computeSummary(c) else null)
}
```

- The summary is **optional** in the envelope: it can cost a pass over the whole scoped set, so a handler
  computes it only when the request asks (the forms listing: with `withStates`). A `null` summary is simply
  not sent.
- Returning a summary from an endpoint that declared none is a handler fault, not an off-contract extra.
- The content hash covers the summary with the items, since it can change while the page does not.
- A per-client copy of the endpoint keeps the declaration, as it keeps the paging flags.

## Source

`base/common/.../endpoint/EndpointBuilder.kt`; kernel constants in
`base/kernel/src/commonMain/.../endpoint/EndpointConstants.kt`; execution in
`base/common/.../http/request/RequestService.kt`. Tests: `base/common/src/test/.../endpoint/`
(`EndpointBuilderTest.kt`, `EndpointCatalogTest.kt`); worked examples in `sample` (`SampleFileService`, for
the file kinds) and `SchemaService`'s `/demo/schema/sample` and `/fixture/schema/complex` (for rich input schema: choices,
dates, numbers, booleans, deep `$ref`s and recursion).
