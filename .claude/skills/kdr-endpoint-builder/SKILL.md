---
name: kdr-endpoint-builder
description: Author HTTP endpoints in KotlinDynamicRuntime using the endpoint builders — generalEndpoint/itemEndpoint/listEndpoint inside schemaModule, the results/item/items response envelopes, protocol fields, and the handler lambda. Use when adding or reviewing endpoints in this codebase.
---

# Authoring endpoints (endpoint builders)

Lives in `base/common`, package `com.dynamicruntime.common.endpoint`. Endpoints
are built **immediately** (the full input/output schema is realized when the
builder runs — there is no deferred construction pass), and the code to run is a
**plain trailing lambda** (no MVC-style indirection). Builds on the schema layer
(see the `kdr-schema-builder` skill).

## The DSL

Declare types and endpoints together for a namespace with `schemaModule`:

```kotlin
val module = schemaModule(cxt, "users") {
    type("UserQuery") { type = SCT.kObject; property("namePrefix", "Filter by name prefix") }
    type("User") { type = SCT.kObject; property("id", "User id") { type = SCT.integer }
                   property("name", "Full name") }

    // GET one resource -> data under `item` (implies 404-on-miss once executed).
    itemEndpoint("/user/{id}", HttpMethod.GET, outputRef = "User") { cxt, request ->
        // return the item (or null); framework wraps the envelope
    }

    // General endpoint -> data under `results` (always a map object). Often POST.
    generalEndpoint("/user/rename", HttpMethod.POST, outputRef = "User", inputRef = "UserQuery") { cxt, request ->
        // return the results map
    }

    // List -> payload under `items`; caller input nested under `request`; adds `limit`.
    listEndpoint("/users", outputRef = "User", inputRef = "UserQuery", hasMore = true) { cxt, request ->
        // return the items list; set paging on the response (KdrResponse, once it exists)
    }
}
// module.defs   -> the $defs types keyed by qualified name
// module.endpoints -> List<KdrEndpoint>
```

`schemaModule(cxt, ns) { … }` returns `SchModule(defs, endpoints)`.
`SchModuleBuilder` extends `SchTypesBuilder`, so all the `type(...)`/`property(...)`
DSL is available alongside the endpoint methods.

## The three kinds (differ by where the payload sits)

Every output also carries `requestUri` (String) and `duration` (number, ms).

- **`generalEndpoint`** → result under **`results`** (a map object).
- **`itemEndpoint`** → single resource under **`item`** (GET-style; 404 on miss when executed).
- **`listEndpoint`** → payload list under **`items`**. The caller's input is nested
  under **`request`** with a **`limit`** sibling (default 100) — nothing is merged
  into the request type (avoids `allOf`). Options: `hasMore`, `hasNumAvailable`,
  `noLimit`. Method **defaults to `GET`** (rarely anything else); note its
  signature is `(path, outputRef, method = GET, inputRef = null, …)`, whereas
  general/item are `(path, method, outputRef, inputRef = null, …)`.

## Signatures

- `HttpMethod` — enum `GET` / `POST` / `PUT`.
- `KdrEndpointHandler = (cxt: KdrCxt, request: Map<String, Any?>) -> Any?` — the handler
  returns the core payload (the `results` map / the `item` / the `items` list). It is
  captured verbatim; there is no name-based indirection.
- `KdrEndpoint(path, method, inputSchema: Map<String,Any?>, outputSchema: Map<String,Any?>, handler)`
  — the schemas are built JSON-schema **maps** with `$ref`s into the module `$defs`
  (parsing/registration is the endpoint loader's job — see below).
- Protocol keys are constants in `object EP` (`EP.results`, `EP.item`, `EP.items`,
  `EP.request`, `EP.limit`, `EP.numItems`, `EP.hasMore`, `EP.numAvailable`,
  `EP.requestUri`, `EP.duration`). `defaultListLimit = 100`.

## Request context

`cxt.request` (`KdrRequest?`) carries the original request data deep in the stack;
`cxt.userProfile` carries identity + `roles`. A `KdrResponse` accumulator (for
`hasMore`/`numAvailable`/status) is planned but **not built yet** — as are endpoint
**execution** and the **endpoint loader** that parses the built schemas and wires a
registry. This skill covers *building* endpoints, not running them.

## Source

`endpoint/EndpointBuilder.kt`; tests `endpoint/EndpointBuilderTest.kt`.
