---
name: kdr-schema-builder
description: Author, review, or apply JSON Schema in KotlinDynamicRuntime using the Sch* layer — the builder DSL (schemaDefs/type/property, SCH/SCT/SFMT constants, required-on-the-side, $ref, reusable clone-and-mutate properties), plus parsing (parseSchemaTypes) and validate/coerceAndValidate with the allowCoerce coercion rules. Use when writing schema definitions or validating/coercing data against them.
---

# Authoring schema definitions (Sch* builders)

The schema layer lives in module **`base/kernel`** (`commonMain`), package
`com.dynamicruntime.common.schema`. It builds JSON Schema (draft 2020-12) as
insertion-ordered `Map<String, Any?>` values via a Kotlin DSL.

It is in the kernel so the **frontend runs the same code**: the webapp's display engine parses an endpoint's
schema with `parseSchemaTypes` and checks input with `coerceAndValidate` — the very functions the backend
runs — so the two cannot disagree about what a schema means. Keep it plain Kotlin over
`Map`/`List`/primitives: no `java.*`, no reflection. (Its tests are on the JVM, in
`base/common/src/test/.../schema/`.)

## The DSL

```kotlin
val defs = schemaDefs(cxt, "core") {                 // namespace named ONCE
    // Reusable properties (declared in the scope's namespace):
    val name = property("name", "A name")            // description MANDATORY for fields
    val active = property("active", "Active flag") { type = SCT.boolean }

    type("Count") { type = SCT.integer; description = "A counting integer" }

    type("Person") {
        type = SCT.kObject
        property(name, required = true)              // reuse (deep-cloned per use)
        property(active) { description = "Currently active" } // clone + mutate
        property("age", "Age in years") { type = SCT.integer }
        property("nickname", "Informal name")        // defaults to type=string
        property("count", "How many") { ref("Count") } // $ref -> #/$defs/core.Count
    }
}
```

`schemaDefs(...)` returns the **`$defs` contents** keyed by fully-qualified
`namespace.Name` (here `core.Count`, `core.Person`). Wrap with
`mapOf(SCH.dDefs to defs)` for a standalone document.

## Conventions (important)

- **Required is on the side.** `property(..., required = true)` records the name
  in the type's `required` array; there is no per-field required flag.
- **Field descriptions are MANDATORY** (`property(name, description, ...)`); a
  type's `description` is optional.
- **Fields default to `string`** unless the build block sets a `type` or a `$ref`.
- **Namespace once.** `schemaDefs(cxt, "core")` defaults the namespace for both
  `type(...)` and `property(...)`. Override per entity: `property(..., namespace = "ext")`,
  or a dotted name like `type("other.Foo")` / `ref("other.Foo")`.
- **`$ref` / `$defs`** are flat, dotted, and JSON-Pointer based:
  `ref("Count")` → `{"$ref": "#/$defs/core.Count"}`; a dotted name passes through.
- **Reusable properties** (`schemaProperty` / the scope's `property(...)`) are
  deep-cloned (depth-capped `Map.deepClone()`) on each use, so the template is
  never mutated.

## Keyword constants

Use constants, never string literals, from `SchemaConstants.kt`:
- `SCH` — JSON Schema keywords. Naming: a plain keyword's name matches its value;
  a leading `$` → `d` prefix (`$ref` = `SCH.dRef`); a Kotlin hard-keyword
  collision → `k` prefix (`SCH.kIf`/`kThen`/`kElse`); and a **kd2-specific**
  keyword's *value* carries a `g-` prefix its name does not — `SCH.allowCoerce`
  is `"g-allowCoerce"` — so a document says which keywords are ours while call
  sites read unchanged (see `SCH.gPrefix`). Standard keywords stay bare.
- `SCT` — `type` values (`SCT.string`, `SCT.integer`, `SCT.kObject`, `SCT.kNull`, …).
- `SFMT` — `format` values (`SFMT.date`, `SFMT.dateTime`, `SFMT.binary`).

## Formats: dates and files

A string field's `format` is how this layer says "this is not merely text". Each case is declared with a
builder helper rather than by setting `type`/`format` by hand, and asked about with a kernel predicate
(`isDateFormat` / `isBinaryFormat`) that the parser, the validator **and the frontend** all consult. Adding a
format means adding a helper and a predicate — not special-casing at each call site.

- **Dates** — `dayOnlyDate()` (`format = SFMT.date`, `yyyy-MM-dd`) or `dateTime()`
  (`format = SFMT.dateTime`). A date format makes the field validate **by parsing** and defaults
  `allowCoerce` to true.

- **Files** — `binaryContent()` → `{"type": "string", "format": "binary"}`. This is **OpenAPI's** spelling for
  a file: a string with a format, because JSON Schema has no binary type. At runtime the value is a
  `ContentData` carrying bytes, **not** text, so the validator passes it straight through — the string checks
  would reject it, coercion would mean `toString()` on a file, and there is nothing to check anyway (the
  content's shape is the MIME type's business, not JSON Schema's). See `kdr-endpoint-builder` for the file
  endpoints built on it.

## Character rules: `visibleOnly`

A string field can refuse characters that have no clearly visible rendering with `visibleOnly = true` (the
custom `g-visibleOnly` keyword, issue #543). The rule is one test on the Unicode General Category: the
ordinary space is allowed, and any other character in a `C` (control, format, private-use, unassigned, lone
surrogate) or `Z` (other spaces, line and paragraph separators) category fails as `badValue`, with a message
naming the code point and its position. That catches a tab or newline, a zero-width space, a bidi override,
and a no-break space that looks like a space and compares unequal. It does **not** catch a look-alike letter
from another script; that is a different problem. Off unless set, and only a plain string may set it -- the
parser refuses it on any other type, and on a date or binary format, where it would constrain nothing.

A string field can also **strip or refuse leading/trailing whitespace** with `g-outerWhitespace` (issue #541),
whose two modes are the [SOWS] values: `trimmed()` sets `"trim"` (strip the edges), `noOuterWhitespace()` sets
`"reject"` (fail a value that carries any, as `badValue`; alter nothing). Absent leaves whitespace alone, as
before -- a string that arrives as a string is otherwise never touched. The cleaning happens **before**
`minLength`/`maxLength`, `const` and `options` are checked, so `" a "` fails a `minLength: 3` rather than
sneaking past on its padding; in validate-only mode `"trim"` checks the trimmed form and passes, the same way
`allowCoerce` validates against the coerced value without emitting it. "Whitespace" here is the kernel's
`<= ' '` test (so a no-break space is *not* outer whitespace -- that is `visibleOnly`'s job), and like
`visibleOnly` it is string-only and refused at parse time elsewhere. Reach for `"reject"` on a code, password
or identifier where silent trimming would hide a paste error; `"trim"` on ordinary free text.

## Choice lists: written down, or sourced at render time

`option(value, label)` writes the choices into the document, and they then **bind**: the validator rejects
anything else with `invalidOption`.

`optionsSource(id)` instead names a callback registered at startup, which is handed the request context and
the property's name and answers with the choices *this caller* should see (issue #413):

```kotlin
property(EI.client, "Which client to look at.") { optionsSource(CLD.clientOptions) }
```

Two things follow, and both are deliberate:

- **The id never leaves the server.** The catalog resolves it as it renders, writing the answer into
  `g-options` and dropping `g-optionsSource`, so every schema consumer — the form engine, the read-only
  outline, a future export — sees an ordinary choice list and needs no second way to have options.
- **A sourced list takes no part in validation.** The callback's answer is never parsed into a `SchType`, so
  there is no path by which one caller's list rejects another caller's value. That is what makes a per-caller
  list safe; a field that must actually be bounded is enforced by its handler, which can say *why*.

Declaring both an `option` and an `optionsSource` fails the boot, as does an id no component registered. Both
checks run in `SchemaService.checkInit`, which is the one moment holding the compiled document and the full
registry together. Register the callback with `optionsProvider(id) { … }` — see `kdr-endpoint-builder`.

**Open lists.** `openOptions()` beside the choices says they are *suggestions rather than a bound*: the
validator stops reporting `invalidOption`, and a data-entry surface draws a combobox instead of a closed
dropdown. Reach for it whenever the list cannot claim to be complete — one drawn from a table, or assembled
per caller. A client may **close** an open list — an open list accepts anything, so bounding it accepts a subset, which is
narrowing rule 2 in a different keyword — but may not **open** a closed one, which widens. While the base is
open a client may also change the *contents* freely, and need not stay within the base's choices: nothing they
put there could accept more than "anything" already did.

**Sharing an attribute across surfaces.** Where several endpoints ask for the same thing, put the shared part
in an extension on the builder, beside the Kotlin class that owns the concept — `clientAttribute()` lives with
`ClientDef` and is called from every field naming a client. The *name* and the *description* stay at each
site: those objects are the key sets of different surfaces, and the descriptions genuinely differ.

## Per-caller field visibility: `visibleWhen`

A property can be shown to some callers and hidden from others with `visibleWhen = "<cfact expression>"` (the
custom `g-visibleWhen` keyword, issues #545, #564). Unlike `optionsSource`, it is **evaluated on the frontend**:
the served schema keeps the keyword unresolved — identical for every caller, so the catalog document can double
as published documentation — and the client hides the field when the caller's delivered cfacts fail the
expression. The catalog response carries those cfacts (its `cfacts` field), holding only the ones a `CFactDef`
marks `toFrontend`.

```kotlin
field(EI.user, "Confine the search to one user — a userId or an email.") {
    emptyIsAbsent = true
    visibleWhen = CFACTS.hasAdminLevel        // shown only to a caller ranking at admin
}
```

**Presentation, never a gate.** Hiding a field client-side does not defend it: request validation runs against
the compiled schema, which still carries it. A handler that accepts a `visibleWhen` field must enforce the same
condition itself — it is the advertise half of an advertise-and-enforce pair, the same relationship
`optionsSource` has with a handler that bounds its own input.

**Optional fields only.** The boot fails on a `visibleWhen` that: is a malformed expression; names a cfact whose
`CFactDef` is not marked `toFrontend` (that fact never reaches the client, so the gate would hide the field from
everyone); or sits on a **required** property (the field is hidden client-side but the schema still requires it,
so a caller it hides could never submit).

## Presentation hints (read-only display)

A type or field may declare **how a read-only surface should display it** (issue #540) — advisory only, with
**no effect on validation**. Set it with `presentation = <a PRES value>` in the build block, and read it back
off `SchType.presentation`:

- `PRES.status` — a verdict field, coloured by its `PSTAT` value (`ok`/`info`/`warning`/`error`).
- `PRES.table` — a **type** whose array is rendered as a table (its properties the columns, one row per element).
- `PRES.identifier` — a value shown monospaced (an id, hash, path, env-var name).
- `PRES.detail` — on a **property** that is an array of objects, inside a `table`-rendered row: render that
  array as its own labelled sub-table on a full-width row *beneath* the main row (master-detail), rather than
  as an inline cell. For a heavy nested array (a database table's `columns`) that would otherwise force the row
  very wide. Ignored on a property that is not a structured array.

```kotlin
type("BootCheckInfo") {
    type = SCT.kObject
    presentation = PRES.table                                    // a list of these renders as a table
    property("name", "The check's name.", required = true) { presentation = PRES.identifier }
    property("status", "The verdict.", required = true) { presentation = PRES.status }
}
```

The point is that an endpoint declares how it wants to be read *beside its schema*, so a diagnostic page
follows the schema (the frontend's `SchemaForm` read-only path and its operator pages honor these) rather than
being hand-coded per endpoint and drifting when a field is renamed. A renderer that does not recognize a value
falls back to ordinary rendering, and the validator never consults it — an endpoint declaring a hint still
validates exactly as before.

## Validation & coercion

Parse the built `$defs` map into resolved types, then validate/coerce data:

```kotlin
val types = parseSchemaTypes(defs, existingTypes = emptyMap()) // resolves $refs; unknown -> KdrException
val type  = types["core.Person"]!!
val failures: List<SchFailure> = validate(type, data)          // collects ALL failures, no transform
val result: SchResult          = coerceAndValidate(type, data) // .value (coerced) + .failures; input never mutated
```

`allowCoerce` (a kd2 keyword, so `g-allowCoerce` on the wire; default **true** for numeric,
**boolean** and date-format types, **false** otherwise — see `coercesByDefault`) governs coercion
of a mismatched value — and changes
validation even when no output is requested:

- number/integer strings → `Long`/`Double`; string ← any non-null (`toString`).
- boolean ← string via `parseExactBool`: a **closed** set of spellings — `true/false`, `t/f`, `yes/no`,
  `y/n`, `1/0`, `on/off`, case-insensitive — blank → null, anything else a `badValue`. Deliberately not
  `toOptBool`, which reads only the first character (so `null` and `nil` would become `false`) and exists
  for CSV.
- date-format string → `Instant`; array/object ← JSON string (`[`→`jsonArray`, else comma-split;
  `jsonMap`), then re-validated element/property-wise.
- Missing required properties with a `default` are injected (deep-cloned), not failed.
- A `binary`-format field is exempt from all of it (see above), though `required` still applies.

`SchFailCode`: `missingRequired`, `invalidOption`, **`wrongType`** (a plain type check
rejected it), **`badValue`** (its content was inspected and failed to coerce). A
parse-driven `badValue` carries the parser exception in `SchFailure.cause`.

## Casts

Don't write `as`/`@Suppress("UNCHECKED_CAST")`. Use `com.dynamicruntime.common.util`:
`toT()` (coerce to a type param), `toJsonMap()` (coerce to `Map<String,Any?>`), and the null-tolerant
`toJsonMapOrEmpty()` / `toJsonListOrEmpty()` for wire values that may be absent.

## Source files

- `base/kernel/src/commonMain/.../schema/`: `SchemaConstants.kt` (SCH/SCT/SFMT), `SchTypeBuilder.kt`,
  `SchTypesBuilder.kt`, `SchParser.kt`, `SchValidator.kt`, `SchType.kt`, `SchProperty.kt`, `SchOption.kt`
- `base/kernel/src/commonMain/.../util/`: `CollectionUtil.kt` (`deepClone`), `ConvertUtil.kt`
  (`toT`/`toJsonMap`/`toOptStr`)
- Tests: `base/common/src/test/.../schema/SchTypeBuilderTest.kt`, `SchValidatorTest.kt`, `SchParserTest.kt`

For building HTTP endpoints on top of this layer, see the `kdr-endpoint-builder` skill.
