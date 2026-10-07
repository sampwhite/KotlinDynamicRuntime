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
val defs = schemaDefs(cxt, "abc.people") {           // namespace named ONCE
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
        property("count", "How many") { ref("Count") } // $ref -> #/$defs/abc.people.Count
    }
}
```

`schemaDefs(...)` returns the **`$defs` contents** keyed by fully-qualified
`namespace.Name` (here `abc.people.Count`, `abc.people.Person`). Wrap with
`mapOf(SCH.dDefs to defs)` for a standalone document.

## Conventions (important)

- **Required is on the side.** `property(..., required = true)` records the name
  in the type's `required` array; there is no per-field required flag.
- **Field descriptions are MANDATORY** (`property(name, description, ...)`); a
  type's `description` is optional.
- **Fields default to `string`** unless the build block sets a `type` or a `$ref`.
- **Namespace once.** `schemaDefs(cxt, "abc.people")` defaults the namespace for both
  `type(...)` and `property(...)`. Override per entity: `property(..., namespace = "ext")`,
  or a dotted name like `type("other.Foo")` / `ref("other.Foo")`.
- **Which namespace** (issues #949, #950). A component's types live under the **owner root** it declares
  (`ComponentDefinition.ownerRoot`): core's are `kdr.<area>` (`kdr.core`, `kdr.gedra`), another owner's
  `<root>.<area>` -- the `abc` above. A client's live under `client.<clientId>`. The boot refuses a component
  namespace off its owner's root unless the module or config opts in by name (`contributesTo = "kdr"`), a type
  declared outside its contribution's root, and a type name declared twice. A plain `schemaDefs` is not judged --
  it is a document, not a contribution -- so a test may use any namespace it likes.
- **`$ref` / `$defs`** are flat, dotted, and JSON-Pointer based:
  `ref("Count")` → `{"$ref": "#/$defs/abc.people.Count"}`; a dotted name passes through.
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
- **The `g-` keywords are a closed list** (`SchGKeywords`, issue #822): a `g-` key that is not one of ours, or one
  of ours whose value has the wrong shape (`g-allowCoerce: "yes"`, a `g-presentation` outside `PRES`), fails the
  parse by name. A key of your own without the prefix is left alone.
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

A string field can also **strip or refuse leading/trailing whitespace** with `g-outerWhitespace` (issues #541,
#765), whose modes are the [SOWS] values: `trimmed()` sets `"trim"` (strip the edges on *every* path),
`noOuterWhitespace()` sets `"reject"` (fail a value that carries any, as `badValue`; alter nothing),
`preserveWhitespace()` sets `"keep"` (leave it alone). The cleaning happens **before** `minLength`/`maxLength`,
`const` and `options` are checked, so `" a "` fails a `minLength: 3` rather than sneaking past on its padding;
in validate-only mode `"trim"` checks the trimmed form and passes, the same way `allowCoerce` validates against
the coerced value without emitting it. "Whitespace" here is the kernel's `<= ' '` test (so a no-break space is
*not* outer whitespace -- that is `visibleOnly`'s job), and like `visibleOnly` it is string-only and refused at
parse time elsewhere.

**A plain string with no declared mode trims on the input path by default (issue #765)** -- so endpoint input
reaches the handler trimmed and its bounds/options measure the trimmed value, while the output/stored path
(validated with `forInput = false`) leaves whitespace untouched. This is why you rarely set anything here: the
common case (ordinary free text) is already covered. Reach for `preserveWhitespace()` (`"keep"`) on the rare
input field whose edge whitespace is content -- a **password** is the canonical case, so its exact bytes are
stored and matched; `noOuterWhitespace()` (`"reject"`) on a code or identifier where surfacing a paste error
beats silently cleaning it; `trimmed()` (`"trim"`) only to force the strip on output/stored values too, since
input already trims. This default is part of the codebase's relaxed-coercion posture (see `code-guide.md`).

## Standard constraints: `pattern`, exclusive bounds, `uniqueItems` (issue #823)

```kotlin
type("Order") {
    type = SCT.kObject
    property("postcode", "A five-digit postal code.") {
        pattern = "^[0-9]{5}$"                               // anchor it: a pattern matches anywhere
        errors { patternMismatch("A postal code is five digits.") }
    }
    property("price", "Price, above zero.") { type = SCT.number; exclusiveMinimum = 0 }
    property("tags", "Distinct tags.") { type = SCT.array; uniqueItems = true; items { type = SCT.string } }
}
```

- **`pattern`** (a plain string only; refused elsewhere, date and binary formats included) fails as
  `patternMismatch`, after edge whitespace is handled, like the other string checks. It is **unanchored**, as JSON
  Schema has it -- `^…$` to constrain the whole value. The dialect is ECMA-262 restricted to what the JVM and the
  browser read alike (`SchPattern`): `$` is the end of the value (not before a trailing newline, as Java's is),
  `.` and `\s` mean what they do in JavaScript, and Java-only or ambiguous syntax -- possessive quantifiers,
  atomic groups, inline flags like `(?i)`, `\A`/`\Z`, class union/intersection, a non-quantifier `{`, `\p{…}` with
  anything but a general category -- is refused by name at parse. There are no flags; write `[Aa]`. The default
  message quotes the pattern, so say what it is *for* in `g-errors`.
- **`exclusiveMinimum` / `exclusiveMaximum`** (numbers only) refuse the bound itself; they share
  `belowMinimum` / `aboveMaximum` with the inclusive pair ("This must be more than 0."). With both kinds on one
  side the stricter wins. Draft 4's boolean form (`exclusiveMinimum: true`) is refused.
- **`uniqueItems`** fails as `duplicateItem` ("Item 3 repeats item 1…"), comparing elements as validated JSON
  values: `1` equals `1.0`, `"5"` coerced to an integer equals `5`, and object key order does not matter.

**Held to its shape** (`SchStdKeywords`, issue #1053). Every standard keyword this layer reads must have a value
of the right kind, or the parse fails naming the keyword and the type or property: `type` is one of the seven `SCT`
values (a *list* of types is not supported); `required` is a list of names; `properties` an object whose values are
schema objects (a `null` is a property not set); `items` an object; `additionalProperties` true or false; `oneOf` a
list; `title`, `description`, `format` and `$ref` text; a bound a number (or text that spells one, as it always
read). Most of these used to be read as though the keyword were absent -- `type: "strng"` constrained nothing.
A keyword the layer does **not** read is still the document's own, whatever its value. Some refusals are of legal
JSON Schema this layer does not read (a schema for `additionalProperties`, a tuple of `items`, `true`/`false` as a
schema), and their messages say so.

**Refused by name** at parse -- on a type, a property, or beside a `$ref`: `enum` (use `options`), `allOf`,
`anyOf`, `not` (legal only inside an `if`/`then`/`else` clause), `dependentSchemas`, and, as before, `oneOf`
without a `discriminator`. A **denylist**: any other keyword this layer does not read stays allowed, so a document
may carry its own. A client's alteration of a type may not change any of these (they take part in validation);
it extends instead.

## Choice lists: written down, or sourced at render time

`option(value, label)` writes the choices into the document, and they then **bind**: the validator rejects
anything else with `invalidOption`. In a raw document each entry is a `{"value", "label"}` object or a bare value
(labeled by itself), mixed as you like; an entry that is neither fails the parse (issue #816). A choice is text, so a list belongs on a plain string (or an untyped field,
which the list makes text); the parser refuses one on any other declared type or on a date or binary format
(issue #815). A value coerced to the field's type -- `7` sent to a coercing string field -- is held to the list
exactly as one that arrived as a string, and likewise to a `const`.

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
registry together. In a **client's own** definitions (its config's types) the fault is dropped instead of
refused, under that config's check mode — see *A client's own definitions* below. Register the callback with `optionsProvider(id) { … }` — see `kdr-endpoint-builder`.

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

A **scoped** endpoint's optional `client` field -- the caller's own client unless they may name another -- goes
one step further: declare it with `overseenClientField(name, description)` (issue #1000), beside `overseenClient`,
the rule that enforces it. It applies `clientAttribute()` and `visibleWhen = CFACTS.isDeploymentAdmin`, so only a
caller who may name another client (an `allClients` administrator) is shown it.

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

**Hiding is not defending.** Request validation runs against the compiled schema, which still carries the field,
so `visibleWhen` is the advertise half of an advertise-and-enforce pair. On **trait data** the enforce half is built
in (issue #830): every write — create, patch, the survey and workflow saves, import — keeps a gated field's stored
value for a caller who fails the gate (left out, sent back unchanged, or sent as null or blank) and refuses a change
to it with a 403 — including removing it by deleting the entry, list element or object that holds it. On an
**endpoint input** a handler that accepts the field must enforce the same condition itself, the same relationship
`optionsSource` has with a handler that bounds its own input. Reads are not gated: anyone who may read the data sees
the field.

**Optional fields only.** The boot fails on a `visibleWhen` that: is a malformed expression; names a cfact whose
`CFactDef` is not marked `toFrontend` (that fact never reaches the client, so the gate would hide the field from
everyone); or sits on a **required** property (the field is hidden client-side but the schema still requires it,
so a caller it hides could never submit). In a client's own definitions the keyword is dropped instead — see
*A client's own definitions* below.

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

## Layouts: `g-layout` (issues #584, #585)

A type may carry a **field layout** — how a friendly form renders its fields — under the custom `g-layout`
keyword. (Always qualify it: the *task* layout, `WfLayout` on a workflow task, orders the traits within a task and
is a placeholder until how a task arranges its traits is designed. Today a type has at most one field layout;
selectors — which already choose a task's display (#788) — are to come down here too, choosing among several.)
It is the one kd2 keyword that is **never read into `SchType`**: a layout varies by surface, not by validity,
so it is read out by its own kernel function into a `SchLayout` held **beside** the compiled types
(`KdrSchemaStore.layouts`, keyed by qualified type name), stripped from the served schema
(`KdrSchemaStore.servedDefs`, what the catalog and the workflow view hand out), and delivered out-of-band. The
wire schema stays documentation-grade; the parser ignores the key.

Declare it with the `layout { }` builder inside a `type("X") { ... }` block or a trait's data block:

```kotlin
type("Questionnaire") {
    type = SCT.kObject
    property("topic", "What this is about.")
    property("hasIssue", "Whether a problem was flagged.") { type = SCT.boolean }
    layout(fragmentFileId = "acme") {
        field("topic", label = "Topic", description = "Pick the subject.")
        field("hasIssue", label = "Has issue?")
        // Form-level copy, overridable per client (issue #641). Keys are the LAYSTR vocabulary.
        string(LAYSTR.formErrorHint, "Fix the highlighted fields and try again.")
    }
}
```

which writes the block the parser reads (`SchLayoutBuilder`; the raw-map form `data[SCH.layout] = mapOf(...)`
is equivalent):

```json
"g-layout": {
  "fragmentFileId": "acme",
  "schemaFields": [
    { "field": "topic",    "label": "Topic", "description": "Pick the subject." },
    { "field": "hasIssue", "label": "Has issue?" }
  ],
  "strings": { "formErrorHint": "Fix the highlighted fields and try again." }
}
```

- The block's vocabulary is the `SL` object (`schemaFields`, `field`, `label`, `description`, `hint`, `errors`,
  `defaultMode`, `required`, `choices`, `fragmentFileId`, `strings`, `mode`). The parser is **strict**: an unknown key on the block or
  on an entry, a present block with no entries, or a non-object value all fail the boot — a layout must never
  parse clean and render nothing.
- **Field order and membership** (`mode`, issue #777): who owns *which* fields render and *in what order* — the
  `SLM` values, set via `layout(mode = SchLayoutMode.…) { … }`. `overlay` (default) — the schema owns order and
  the full set, the layout only annotates (historic behavior). `reorder` — the layout's listed fields draw
  first, in list order, then every other declared field in schema order (nothing hidden). `authoritative` —
  only the listed fields render, in list order; the boot then requires the list to name every **required,
  non-derived** property (an omitted required field could never be submitted). All three **narrow and order,
  never widen**: a field the schema hides (`g-derived` in a friendly form, a `g-visibleWhen` the caller fails,
  a forbidden field) stays hidden even when `authoritative` lists it. The kernel decides the order in
  `orderedFieldNames(type, layout)` (`SchFormPlan.kt`) — the render loop calls it and applies its gates on top,
  so the same rule is JVM-testable and browser-run.
  **Why a mode (issue #834):** two kinds of author. A *form author* lives with the schema's defaults and tweaks
  copy or order (`overlay`/`reorder`); an *application builder* treats the field layout as the source of truth
  for every GUI decision (`authoritative`). For the latter, repeating the schema — listing every field, even in
  the schema's own order — is **intended**, not redundancy to remove. Guidance, not a rule: a change that does
  not alter the schema's API semantics (order, copy) belongs in the field layout rather than a schema overlay.
- **Default handling** (`defaultMode`, issue #709): a per-field `field(..., defaultMode = SLDM.filled)` says how
  a **supplied default** (a value the backend hands the form that a person did not enter — a `prefillData`
  default, later other sources) is presented: `SLDM.filled` (shown in the control, marked) or `SLDM.offer` (an
  empty control beside a "use it" affordance). A closed set (a bad value fails the boot); absent leaves the
  surface's own default (`filled`). It is about the *field*, not the source, so it never names `prefill`.
- **Form requirements** (`required`, `choices`, issue #1022): what *this form* asks for beyond the schema --
  `field(name, required = true)`, and `field(name, choices = listOf(SchLayoutChoice("park", "Outdoors"), …))`, a
  **restated** list of the schema's choices (which, in what order, with the form's labels). They never change what
  data is valid: they are checked by one kernel rule, `formRequirementFailures` (`SchFormRules.kt`), on a
  workflow's save, in its task status and on the page, and never by a general data edit. **A workflow does not
  alter schema** -- this is how a workflow asks for a field or offers fewer choices, through its `types`
  alteration's `g-layout`. They apply on top of the schema's conditionals: `required` is ignored while the
  schema's `if`/`then`/`else` withdraws the field, and the form offers only the choices the schema currently offers
  (`offeredChoices`, the one place the two lists meet). `required` only adds (`false` is refused); `choices` must
  name values the schema offers, on a field with a closed list; and a field nobody could always fill in (derived,
  or gated by `g-visibleWhen`) may not be required -- all checked at load. A workflow entry replaces the shared
  entry for that field whole, so restate its copy beside a requirement.
- **Form-level strings** (`strings`, issue #641): a `{ LAYSTR-name → copy }` block of overridable wording a form
  shows for the type as a whole rather than for one field — declared with `string(LAYSTR.key, "...")`. The keys
  are the closed `LAYSTR` vocabulary (an unknown one fails the boot, like every other layout key); the default
  copy for each lives on the surface that renders it, so a layout carries only overrides. A client may override
  these on their variant like any other presentation key.
- Only a **named type's own top-level** `g-layout` is collected. One nested on an inline sub-object is
  **refused** at boot (pull the sub-object out as a named type), not ignored.
- **Boot check** (`checkLayouts`): a layout naming a field its type does not declare, or sitting on a union or
  array type, refuses the boot. A client that narrows a type inherits the base layout by reference and has it
  **pruned** to the properties it kept — a sanctioned narrowing never fails the boot.
- A client may overlay a type's `g-layout` freely: it is in the narrowing allowlist as a presentation key. The
  alteration **merges with the inherited layout by field** (issue #985, `layoutMergeSpec`): it states only the
  `schemaFields` entries it changes, each replacing the inherited entry for that field whole, and `strings` merge
  by key — everything else is global's. An entry for a field the inherited layout does not list is appended under
  `overlay` mode and refused under `reorder`/`authoritative` (it would need a place in the order). An alteration
  that **sets** `mode` to `reorder` or `authoritative` instead **restates** `schemaFields`: its list is the order
  (and, authoritative, the membership), and an entry naming only its `field` keeps the inherited entry. With no
  inherited layout the client's stands as written, and `g-layout: null` drops the inherited one. A layout a client
  writes that would fail the boot check, or whose merge refuses an entry, is dropped instead (the type falls back
  to global's layout, or none) — see *A client's own definitions* below. The merge itself is the general declared
  merger in `common/overlay/OverlayMerge.kt`; `schemaTypeMergeSpec` is the whole of how an alteration applies.
- **`properties` in an alteration** is the restated set by default (`{}` keeps a property, an unmentioned one is
  dropped). An alteration may say `"g-merge": { "properties": "merge" }` to merge instead: it names only the
  properties it changes (`{}` keeps one, `null` removes one) and inherits the rest, including any the base gains
  later. `g-merge` is a directive read by the merge, never part of the type; a choice a part does not offer refuses
  the alteration, and a `g-merge` anywhere but the top of a client's alteration of a global type — a global type,
  a client's own type, a nested part — does nothing and is refused like any misplaced keyword. Either way the
  narrowing check judges the merged result.

**Substitution — two passes, by prefix (issues #586, #587, #605).** Layout copy (`label` / `description` /
`hint`) carries two kinds of template block, resolved in different passes:

- **Frontend `${…}`**, resolved at render. A **`hint`** templates over the field's **bounds** — `${min}` /
  `${max}` resolve to the field's declared `minimum` / `maximum`, replacing the derived `range: X to Y`. A
  **`label`** / **`description`** templates over the **field's own data** — `${someField}` reads the value being
  entered.
- **Backend `%{@t("…")}`**, resolved **server-side at delivery** (`MarkdownFragmentService.backendPass`, the
  mechanism task labels use), for shared copy pulled from a **backend** fragment file. A two-part key
  `%{@t("ns.key")}` resolves against the block's `fragmentFileId`; a three-part `%{@t("fileId.ns.key")}` names
  its file outright. Only the finished copy ships (backend fragment files are private, never served). The two
  prefixes coexist in one string: `%{@t("help.topic")} for ${topicField}` resolves the pull on the backend and
  the field value on the frontend.

Boot checks (`layoutTemplateProblems` + `SchemaService.checkLayouts`): a **malformed** `${…}` **or** `%{…}`
block, a **hint** referencing a bounds param its field lacks (`${max}` with no maximum), and a **frontend**
`${@t(...)}` (a fragment pull must use the backend `%` prefix) all fail the boot. A field-data `${…}` is not
boot-checked (dynamic) — an unresolvable one renders **as written** with a `[kdr]` console warning. Whether a
literal `%{@t}` pull actually resolves — its file exists, is a backend file, and holds the key — is checked at
boot too (issue #620: `SchemaService.checkLayoutPulls`, run from `LayoutCheckService`, which holds the fragment
registry the schema service cannot reach). Only a **computed** (`%{@t(chosenKey)}`) or guarded (`?:`) pull is
left to delivery, where an unresolvable one degrades to the copy as written with a `[schema]` warning.

**Delivery (issues #585, #835).** Both friendly surfaces carry a `fieldLayouts` map beside their `$defs` —
the endpoint catalog under `EI.fieldLayouts`, the workflow view under `WVF.fieldLayouts` — built by one call,
`KdrSchemaStore.layoutsFor(closure)`: `{ typeName → g-layout block }` for exactly the types the served
closure carries that declare one (a type with none has **no entry**), each re-serialized from the store's model
(`SchLayout.toJsonMap()`), so a narrowed client's page receives the pruned form. The frontend reads it with the
kernel's `parseDeliveredLayouts` (the same strict parser the boot ran) into `Catalog.fieldLayouts` and
`WorkflowView.fieldLayouts`, joined to a type by its qualified name; a workflow trait carries its own under
`WfTraitView.fieldLayout`, and a form takes the map as `FormOpts.fieldLayouts` / `SchemaFormProps.fieldLayouts`.
Outside the schema package the delivered form is always named `fieldLayout(s)`, so a search finds exactly it —
not the task layout (`WfTask.layout`), a page layout, or prose.

## Extensions: a named type as another plus a delta (`g-extends`, issue #990)

A **new** named type may be declared as an extension of another, instead of being written out in full or composed
by a `$ref` property: `type("Wide") { extends("kdr.B"); property("extra", "…") }`, which writes
`"g-extends": "kdr.B"` at the top of the `$defs` entry (a bare name resolves in the builder's namespace).

- **Resolved before parsing** (`resolveExtensions`, `SchExtends.kt`), by #985's merger with `extensionMergeSpec`:
  **`properties` merges by default** -- a named property is added or replaces the base's whole, `{}` keeps it, `null`
  removes it, and an unnamed one is the base's, including any the base gains later; `"g-merge": { "properties":
  "restate" }` states the whole set instead. `g-layout` merges by field, as an alteration's does; every other key
  replaces (so a `required` the extension writes replaces the base's).
- **A new name, so no narrowing check**: nothing that refers to the base sees the extension, and it may widen. It is
  checked as an ordinary type once resolved, and a trait uses it like any named type (`dataType = "client.acme.Wide"`).
- **A client's view of the base**: a client's extension resolves on the client's composed document, after its
  alterations, so it extends the base as this client has it. A component's (global) extension resolves on the global
  document, before the global repair and parse -- **once**, so it does not follow a client's alteration of its base:
  a client that narrows `kdr.B` leaves a global `kdr.A` extending it accepting what was narrowed away, unless the
  client alters `kdr.A` too.
- **Design View's shared editor refuses an extension** (`DesignRefusal.extendsType`): its stored entry is only the
  delta, so the fields it inherits are not there to edit. It is changed through its configuration, where writing
  the delta's own `g-layout` merges with the base's by field.
- **The served schema carries the resolved type**; `g-extends` and `g-merge` never reach `SchType` or a reader.
- **Refused**: an unknown base, a base that is itself an extension (no chains), a base that is not an object type, a
  merge choice the spec does not offer, `g-extends` anywhere but the top of a new named type (an alteration keeps the
  name it alters, so it cannot extend), and an extension of a type its **own** configuration also alters (another of
  the client's configurations altering it is fine). Strict outside production for source config and in unit tests
  for stored config; otherwise the extension is dropped and reported, as any client definition fault is.

**A `$ref` takes its type whole.** A schema key beside a `$ref` -- `properties`, `type`, `maxLength` -- used to parse
clean and do nothing; it is now refused at all three sites (a property, an array's `items`, a union branch), naming
the key and pointing to `g-extends`. What may stand beside a `$ref` is what the site reads about its own use: a
`description` and `title` anywhere, and on a property `g-optionalContents`, `g-presentation` and `g-visibleWhen`
(plus `$comment` and other off-contract annotations).

## A client's own definitions: dropped, not refused (issue #841)

The boot checks above refuse the boot for a fault in a **component's** schema -- outside production; in production
the same repair runs on the global document and the fault is dropped, as every source-config check degrades there
(`KDR_GEDRA_CONFIG_CHECK`). A document that will not *compile* still refuses everywhere. A fault in a **client's** own
definitions — the types its config declares or alters, which may be stored in the database — costs only itself:
as the client's variant is built (at boot and on a reload alike), the smallest faulty piece is dropped, logged,
and recorded on the client (`ClientConfigIssues`). Whether that forgives or refuses is the holding config's check
mode: stored config is forgiven everywhere but `unit` (`KDR_STORED_CONFIG_CHECK`), source config refuses outside
`prod` (`KDR_GEDRA_CONFIG_CHECK`).

| fault | what is dropped |
|---|---|
| unregistered `g-optionsSource`, or one beside `options` | the keyword (the field takes free input, or keeps its options) |
| bad `g-visibleWhen`, or one on a required property | the keyword (the field shows for everyone) |
| a `g-errors` message that cannot render, or an unknown code | that message |
| a standard keyword of the wrong shape (`type: "strng"`, `required: "name"`, issue #1053) | the keyword -- or only the part at fault: a property whose schema is not an object, a `required` entry that is not a name. For most that is how it was already read, so the definition validates as it did; a bound that is not a number, a non-text `$ref`, or a non-type beside `g-options` used to stop the whole type compiling and now costs only the keyword |
| a client-written `g-layout` that fails the layout check | that layout |
| a type that will not compile (an unresolvable `$ref`) | that type change -- and a trait whose type it was, which then leaves the client's supported set |
| a client cfact redeclaring a global one, or declared twice | that declaration |

The repair runs on the **raw** definitions (`repairTypeDef`, `ClientSchemaRepair.kt`) with the boot checks' own
helpers, so the later whole-document checks find nothing left in the client's variant.

## Validation & coercion

Parse the built `$defs` map into resolved types, then validate/coerce data:

```kotlin
val types = parseSchemaTypes(defs, existingTypes = emptyMap()) // resolves $refs; unknown -> KdrException
val type  = types["abc.people.Person"]!!
val failures: List<SchFailure> = validate(type, data)          // collects ALL failures, no transform
val result: SchResult          = coerceAndValidate(type, data) // .value (coerced) + .failures; input never mutated
```

Where a malformed document is expected, not a fault -- a client's own definitions, an editor -- read it as a report
rather than catching the throw (issue #909): `analyzeSchemaTypes(defs, existingTypes)` returns `.types`, or null
with `.problems` holding the first fault, coded (`SchemaError`) and located at its place in the document
(`acme.Q.properties.topic.items`; a `pattern` fault adds its offset). The message is the throwing form's, word for
word. `SchGKeywords.problem` and `refusedKeywordProblem` return the same `Problem`.

Layouts and narrowing report the same way: `parseSchLayoutResult` / `parseTypeLayout` collect every problem with a
`g-layout` block, and the boot checks (`layoutFieldProblems`, `layoutTemplateProblems`, `layoutPullProblems`,
`layoutBackendBlockProblems`, `narrowingProblems`) return `Problem`s coded by `LayoutError` / `NarrowingError`,
located within the block (`schemaFields[2].hint`, plus the template offset) or the type
(`acme.Q.properties.kind.g-options`). Their messages are unchanged; a caller joining them reads `.message`.

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
rejected it), **`badValue`** (its content was inspected and failed to coerce), `additionalProperty`,
`notAllowed`, `belowMinimum` / `aboveMaximum` (any bound), `patternMismatch`, `duplicateItem`. A
parse-driven `badValue` carries the parser exception in `SchFailure.cause`.

## Casts

Don't write `as`/`@Suppress("UNCHECKED_CAST")`. Use `com.dynamicruntime.common.util`:
`toT()` (coerce to a type param; refuses null) / `toOptT()` (its nullable counterpart), `toJsonMap()` (coerce to `Map<String,Any?>`), and the null-tolerant
`toJsonMapOrEmpty()` / `toJsonListOrEmpty()` for wire values that may be absent.

## Source files

- `base/kernel/src/commonMain/.../schema/`: `SchemaConstants.kt` (SCH/SCT/SFMT), `SchTypeBuilder.kt`,
  `SchTypesBuilder.kt`, `SchParser.kt`, `SchValidator.kt`, `SchType.kt`, `SchProperty.kt`, `SchOption.kt`,
  `SchPattern.kt` (the portable `pattern` dialect), `SchGKeywords.kt` (the closed `g-` keyword list),
  `SchLayout.kt` (the `g-layout` model, its `SL` vocabulary, collect/strip/prune and the boot check)
- `base/kernel/src/commonMain/.../util/`: `CollectionUtil.kt` (`deepClone`), `ConvertUtil.kt`
  (`toT`/`toJsonMap`/`toOptStr`)
- Tests: `base/common/src/test/.../schema/SchTypeBuilderTest.kt`, `SchValidatorTest.kt`, `SchParserTest.kt`

For building HTTP endpoints on top of this layer, see the `kdr-endpoint-builder` skill.
