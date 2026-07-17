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

Declare types and endpoints together for a namespace with `schemaModule`:

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

## The kinds (differ by where the payload sits)

Every JSON output also carries `requestUri` (String) and `duration` (number, ms).

- **`generalEndpoint`** → result under **`results`** (a map object).
- **`itemEndpoint`** → single resource under **`item`**.
- **`listEndpoint`** → payload list under **`items`**, with `numItems`; options `hasMore`,
  `hasNumAvailable`, `noLimit`. Method **defaults to `GET`**, and note the parameter order differs:
  `(path, description, outputRef, method = GET, …)` against general/item's
  `(path, description, method, outputRef, …)`.
- **`fileUploadEndpoint`** / **`fileDownloadEndpoint`** → see *Files* below.

## Input is FLAT

An endpoint's input is a flat set of top-level fields, so the flat HTTP input — query params and/or the body —
validates directly with no re-grouping. Declared one of two mutually exclusive ways, or neither for a
no-parameter endpoint:

- **`inputRef`** — a named type whose top-level properties become the fields.
- **`inputFields`** — declared inline. `field(name, description, required = false) { … }` mirrors
  `property(...)`: description mandatory, type defaults to string unless the block sets a `type`/`$ref`.

A list endpoint appends a **`limit`** field (default 100) as a plain sibling, unless `noLimit`. The resolved
input type is always closed to undeclared properties (`additionalProperties = false`); off-contract `_`/`$`
keys stay exempt.

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
- **Never let an uploaded filename choose a path on disk** — it is attacker-supplied. Store under an id you
  generate and keep the name as metadata; see `SampleFileStore` in the `sample` module.

## Signatures

- `HttpMethod` — enum `GET` / `POST` / `PUT` (kernel).
- `EndpointKind` — enum `general` / `item` / `list` / `file` (kernel).
- `KdrEndpointHandler = (cxt: KdrCxt, request: Map<String, Any?>) -> Any?` — returns the core payload
  (the `results` map / the `item` / the `items` list / a `ContentData`). Captured verbatim; no name-based
  indirection.
- `KdrEndpoint(path, method, kind, namespace, description, inputFields, inputTypeRef, includeLimit,
  outputSchema, handler)` — the output schema is a built JSON-schema **map** with `$ref`s into the module's
  `$defs`. The *input* is not realized here (a type ref cannot be flattened until its target is bound); it is
  resolved on demand by `resolveEndpointInputType` against the compiled types.
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
- **`errorMessage`** — the message (`fullMessage()`, i.e. the cause chain). Still the raw message for every
  code today; fragment-resolved copy and 5xx redaction are later phases of #97.
- **`requestUri`** — as on a success response.
- **`extraData`** — whatever remains of the exception's bag (e.g. `offset` / `line` / `lineCol`), **nested**
  under its own key so it can never shadow a protocol field. Absent when empty.

## Request context

`cxt.request` (`KdrRequest?`) carries the original request data deep in the stack; `cxt.userProfile` carries
identity + `roles`. `cxt.request.responseMeta` is a mutable map a handler can add to; if non-empty it travels
on the response under the off-contract `_meta` key.

A `KdrResponse` accumulator (letting a list handler report `hasMore` / `numAvailable`) is **still not built** —
those envelope fields are declared by the builder but not yet populated by execution.

## Source

`base/common/.../endpoint/EndpointBuilder.kt`; kernel constants in
`base/kernel/src/commonMain/.../endpoint/EndpointConstants.kt`; execution in
`base/common/.../http/request/RequestService.kt`. Tests: `base/common/src/test/.../endpoint/`
(`EndpointBuilderTest.kt`, `EndpointCatalogTest.kt`); worked examples in `sample` (`TodoService`,
`SampleFileService`) and `base/kdn/.../demo/DemoEndpoints.kt`.
