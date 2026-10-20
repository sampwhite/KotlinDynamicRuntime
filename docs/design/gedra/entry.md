# Gedra entry

The design of the universal stored entity, the schema constructs it needs, and how a stored gedra is changed. It
began as a **declaration of intent**, written before the code; issue #240 tracked both this document and the
schema work it called for. Most of it has since been built, and each section says where it still describes intent.

It is a companion to [`docs/development/code-guide.md`](../../development/code-guide.md) (that file says how we write
code, this one says what one particular subsystem is), and to [`config-and-data.md`](config-and-data.md), which
describes ids, visibility, and everything around an entry. State entries, the second family of entries a gedra
carries, have their own document: [`states.md`](states.md).

Decisions are stated plainly. Where something is genuinely unsettled it appears under
[Open questions](#open-questions) rather than being papered over. Where something was considered and postponed it
appears under [Deferred](#deferred), so a later reader can tell a deliberate omission from an oversight; items there
are promoted to [`docs/development/deferred-work.md`](../../development/deferred-work.md) as their triggers become
concrete.

## Vocabulary

- **Gedra**: the universal top-level entity, a vessel that carries any cargo. Also the product's brand name, on the
  Jira model where the brand and the colloquial unit name are the same word. Hard G. Domain-model tier, so no `Kdr`
  prefix.
- **Gedra kinds**: what a gedra *is*. The data kinds are `userData`, `formDoc`, `wfData`, and `fileRef`
  (`GedraDataType`). Configuration, workflows included, is stored as the config kind `configDoc`.
- **The tables**: `GedraDataTran` is the transaction root, one row per gedra, and the lock a write takes.
  `GedraData` holds the gedra's data and `GedraDataStates` its state, both under that one lock. `GedraDataRow` is
  one data row read back, entries included.
- **Entry**: one schema-defined unit stored on a gedra. A gedra holds many, usually of differing traits. They are
  an array under the `data` map of the gedra's one `GedraData` row (#310), so primary-key uniqueness is an
  application check rather than a unique index, per-entry audit is fields rather than columns, and locking is per
  gedra rather than per entry.
- **Trait**: the definition an entry is an instance of. It binds a schema to a **`traitId`**, and is held as a
  `GedraTrait` (a config trait, stored on a config bundle, is a `GedraConfigTrait`).

A gedra is a composition of traits; an entry is one trait's worth of data.

## The shape of an entry

A stored entry, as it reads back today:

```json
{
  "traitId": "sample:expenseReport",
  "data": { "year": 2024, "perItemAmount": 120.5, "itemCount": 40 },
  "entryId": "20240301040203566KxQz",
  "source": "user",
  "createdBy": 232,
  "updatedBy": 265,
  "createdAt": "2020-01-05T06:02:03.256Z",
  "updatedAt": "2023-03-01T04:02:03.566Z"
}
```

| field | status | what it is |
|---|---|---|
| `traitId` | built | Which trait this entry carries. |
| `data` | built, required | The trait's payload, and the only part whose shape varies. |
| `entryId` | built, required | The entry's stable opaque id; see [Identity](#identity). |
| `source` | built, required | Who is accountable for the current value. **Mutable.** |
| `createdBy`, `updatedBy`, `createdAt`, `updatedAt` | built, required (#325) | Per-entry audit. |
| `origin` | not built | How the entry arrived. **Immutable.** |
| `lockedBy` | superseded | Locks are declared on the workflow instead (`WfLock`, #857), not stored on the entry. |

The envelope fields are all `g-derived`: a caller never supplies them, and the stored entry always has them
(`storedEntryFields`).

- **`traitId`** is the discriminator: it decides the shape of `data`. The `Id` suffix is load-bearing, because
  traits are authored at runtime and a bare `type` would read like a closed set the code knows about.
- **`source`** says who is accountable for the *current* value. Today it is `user` for everything stored, and
  `prefill` only on a view-only entry that a `prefillData` function offered and nobody stored (`GSRC`). The intent
  is that an import writes its channel, `excel` say, while the value remains an unreviewed suggestion, and that a
  human edit or approval flips it to `user`.
- **`origin`** is intended to record once, at creation, how the entry arrived: `channel` plus whatever detail that
  channel carries, such as `{"channel": "excel", "fileName": "xxx.xlsx"}`. It is absent when the entry was created
  by a direct endpoint call.
- **`createdBy` / `updatedBy` / `createdAt` / `updatedAt`** are per entry, not per gedra, and named to match the
  four audit columns every table already carries (`PF` in the SQL layer). An entry is meant to read like a row, so
  the resemblance should go as far as the spelling: `modifiedAt` here and `updatedAt` one layer down would be two
  names for one idea, and whoever wrote the second would have had to choose. The two `By` fields hold the actor,
  not the gedra's owner, since they differ exactly when an administrator edits somebody else's document.

### Why `source` and `origin` are separate

They were one field for most of the design discussion, and separating them again is the point worth recording.

`source` mutates and `origin` does not. A user edit flips `source` to `user`; an approval flips it in bulk
across every trait the approval covers, marking values the approver never touched as ones they now vouch for.
"Which values has nobody vouched for" is then a plain equality filter, which is most of what the missing-data
GUI needs.

`origin` describes an event that already happened and cannot stop being true. Held in one object with `source`,
an approval either destroys the file reference — losing the answer to "where did this number originally come
from," the first question an audit asks — or leaves `{"source": "user", "fileName": "xxx.xlsx"}`, which reads
as a contradiction. Split, both are simply true: this value is user-attributed, and the entry arrived from that
file.

`origin.channel` records the arrival channel independently of `source`, so the original channel survives the
flip even for channels that carry no other detail.

The names are near-synonyms, and the distinction is carried by mutability rather than by the words. The shape
difference — a string against an object — is the cue at each call site. `attribution` is the mutable one's
more honest name if the ambiguity ever costs more than the rename would.

### Identity

An entry's identity is **not** its position in a list, and not its `traitId` alone: several entries may share
a `traitId`, distinguished by their primary key (see [`g-primaryKey`](#g-primarykey)).

A primary key is drawn from user-editable data, so it is **mutable identity** — correcting a year turns an
entry into a different entry, and anything holding a reference is left pointing at nothing. So the two jobs
stay separate:

- **`g-primaryKey` declares uniqueness and human-facing addressability.**
- **A stable opaque entry id carries identity**, and is what references and staleness checks use.

**An entry is addressed by its trait and its key, in brackets** (#977): `sample:yearly[2024]`,
`quarterly[2024,Q1]`, and a bare trait id for an unkeyed trait (`entryAddress` in `GedraEntry.kt`). An index such as
`entries[3].data.year` shifts on insert; a keyed address survives. The bracket is the separator a colon cannot be,
since a colon roots a global trait id (`kdr:name`, #951). A key value is written bare when it can be and
JSON-quoted when it cannot, and text and number spell the same entry (`canonicalKey`). A report path is the first
consumer: the address, then the field, as in `form.sample:yearly[2024].note` (`parseReportPath`). Validation
failure paths still spell `entries[3].data.year`.

**A new entry's `entryId` is `mkUniqueId`** (#310), minted whenever a create, import, or patch makes an entry. It is
globally unique rather than unique within its row, which is more than is needed but free: entries survive being
exported into a different gedra, and an id pasted into a log search identifies one entry without knowing which
gedra it came from. A per-row counter was considered and rejected twice. Incrementing past the largest existing id
reuses a number once the highest entry is deleted, so a held reference silently points at a different entry, which
is fatal for the field that exists to be the **stable** surrogate. A stored high-water mark fixes the reuse, at the
cost of a counter every write path has to maintain. The id costs about 21 characters per entry, accepted as part of a
deliberately verbose storage strategy; compression is the answer if stored size ever becomes a real cost.

This is one instance of a rule the design applies more than once: a stored reference points at a stable
surrogate, while the meaningful name is a lookup key and a label. The other is any future reference between
entries.

## Traits

A trait binds a `traitId` to a schema, together with functional and permission directives. The functional ones
built so far are registered by components and bound to a trait by its id: save-time functions
(`GedraPrepForSaveFn`, #728) and on-read data derivers (`GedraDataDeriver`, #712). Only the schema binding is
designed here; permission directives are [deferred](#deferred).

### The binding is explicit

A `traitId` names its schema explicitly. Deriving one from the other by naming convention is rejected: the
convention holds right up until a client names something badly, and by then stored data depends on it.

This is the same principle as declaring the discriminator rather than deducing it, and as the surrogate id
above — the system records intent instead of re-deriving it.

It also means the branch `const` values in the generated schema (below) come *from* the binding rather than
being written alongside it, so there is one authority and nothing to drift.

### Scopes

Traits come from two places, and their ids are kept apart by how they are spelled rather than by a precedence
rule (#807, #951):

- **Global traits** are declared in source by a component. Each id is **rooted** with its owner's root:
  `kdr:name` from core, `sample:yearly` from the sample component.
- **Client traits** are declared in a client's own configuration. Each id is **bare**: `survey1`.

A rooted id and a bare one can never be equal, so a client's trait never shadows a global one, and no release
adding a global trait can take an id a client already uses. Two clients may each declare the same bare id and
get their own trait. A stored entry carries only the id; the gedra holding it names its client, so the id
resolves among that client's own traits and the global ones, with never two answers.

**Only the generated types are namespaced.** A trait generates an entry type and a data type, and those names live
in the declaring owner's namespace: core's under `kdr.core`, a client's under `client.<clientId>` (#949). The
namespace scopes the types, never the trait ids. Namespacing the ids would break every stored entry, because the
stored form has no namespace to resolve against. See [`config-and-data.md`](config-and-data.md#visibility) for the
ownership rules.

**A client may alter a global type** by declaring a type under the global type's own name. The alteration applies
to that client alone, and it may only narrow (see [Augment or extend](#augment-or-extend)).

A worked example, with two clients, `acme` and `globex`:

| declared by | trait id | generated types |
|---|---|---|
| core (global) | `kdr:name` | `kdr.core.NameEntry`, `kdr.core.NameData` |
| sample component (global) | `sample:yearly` | `sample.traits.YearlyEntry`, `sample.traits.YearlyData` |
| `acme` | `survey1` | `client.acme.Survey1Entry`, `client.acme.Survey1Data` |
| `globex` | `survey1` | `client.globex.Survey1Entry`, `client.globex.Survey1Data` |

- An `acme` form and a `globex` form can each hold an entry with `"traitId": "survey1"`. The two are different
  traits with different shapes, and each resolves through the client named in its gedra's id.
- Neither client can name the other's types. Each one's schema is the global document plus its own declarations,
  so the other's are not there to resolve.
- `acme` also declares a type named `sample.traits.YearlyData` that makes `note` required. That is an alteration,
  not a new type. In `acme`'s schema every `$ref` to `sample.traits.YearlyData` now resolves to the narrowed form,
  so both `acme`'s entry union and its edit union require a `note`. For `globex`, and for anything reading
  `acme`'s data through the global document, the type is as the sample component declared it, and `acme`'s
  entries are still valid there.
- A `sample:yearly` entry stored by either client is the same trait, under the same rooted id.

A workflow does not alter schema (#984, #1022). It may vary the presentation of the types its pages show, and
for now only their field layout (`g-layout`). What a task requires of a form lives in the layout and on the
task, not in the schema; see [Tasks declare what is owed](#tasks-declare-what-is-owed).

### Augment or extend

Two moves, and which one applies follows from **storage identity**:

- **Alter (overlay)**: the same type name, therefore the same trait and shared storage, therefore **narrowing
  only**.
- **Extend**: a new type, and a new trait if it is to be stored separately, therefore broadening is legitimate.

**Narrowing binds the write path, never the read path.** An alteration decides what a client may write. The global
type accepts a superset of anything an alteration could have written, so no alteration can produce data that a
reader holding only the global type cannot read back. That is *why* narrowing-only is safe. It is also why dropping
a property the global type requires is refused even though it reads as narrowing: data omitting it would then fail
for every reader who is not this client.

**Alterations may not touch behavior.** Narrowing a schema is safe because the base still accepts the result;
there is no equivalent guarantee for code. If one client could swap a save-time function while another could not,
two clients would write differently processed data under one trait, and the base definition would no longer
describe what is stored. Code is bound to a global trait id, and a behavior change requires an extension.

Narrowing is checked against a closed list rather than inferred (`SchNarrowing.kt`, #356): fewer properties, fewer
choices (or a choice list where there was none), and more required. Anything else that takes part in validation
is refused, and the offending alteration is dropped, leaving the global type in place. Alterations merge **raw
documents, re-parsed**, never parsed objects. By default an alteration restates what it changes; `g-merge` (#985)
lets it merge a part entry by entry instead, so `{"g-merge": {"properties": "merge"}}` keeps every property it does
not mention, and a `null` entry removes one.

### Extension

**Type extension is built** (`g-extends`, #990). A named type may name one type it extends; it is that base plus
its own body, merged before the document is parsed, so it is served and validated as one resolved type. A client's
extension extends the base as that client has it; a global extension is resolved once, so it does not follow a
client's alteration of its base. An alteration keeps the name it alters, so it cannot also be an extension.

**Trait-level extension is intent.** The design is that an extension of a *trait* records its base, and that the
link is live rather than a copy taken at authoring time: the extension would inherit its base's save-time
functions and other behavior, and a copy would leave every extension stranded on the version of the behavior that
existed when it was made. The cost is that a change to a base propagates schema *and* code into every descendant,
so the compatibility checker would need the descendant graph. That is the main reason the edge would be stored
rather than merely performed.

### Two populations

The global pool is not homogeneous:

- **Code-backed traits** ship with a release and carry real functionality — a DocuSign integration and its
  kind. A client cannot author one, and extending one may not violate the assumptions its code makes.
- **Data-authored traits** are pure definition, created by clients.

### Unresolved traits

A `traitId` that does not resolve is **opaque, never an error, and never dropped on write-back**: its `data` is
carried as a plain map. Code that reads entries is generally handed a limited set of trait-to-type bindings and
ignores what it does not recognize, which is what lets a query span clients whose definitions it has never
seen.

Unresolved **behavior** is the opposite case and must fail loudly. Unknown schema means some data cannot be
interpreted; unknown behavior means data would be stored looking processed when it is not.

### State entries

A gedra also carries **state entries**: what a process records about a gedra, as distinct from the data a person
enters. They are a separate family with their own union and table, and they are not written by a patch. See
[`states.md`](states.md).

## Schema constructs

Four things the `Sch*` layer did not have when this was written, and has since gained (`SchVariants`,
`SchCondition`, `g-primaryKey`, `g-derived`). `SchType` had no variant or conditional representation.

The parser **ignores a standard keyword it does not read**, deliberately, since being strict about standard
keywords would reject documents a stock validator accepts. Three qualifications:

- A standard keyword it *does* read is held to its shape (#1053), so `type: "strng"` is refused rather than read
  as no type at all.
- Some legal JSON Schema forms it does not support are refused outright: a list of types, a tuple of `items`, and
  `true` or `false` standing for a schema (`SchStdKeywords.kt`).
- **Our own `g-` keywords are strict** (#822): an unknown `g-` keyword, or one of ours with a value of the wrong
  shape, fails the parse.

A fifth came later (issue #1055): **an object that is a map**. `additionalProperties` given as a schema, rather
than as true or false, says what every undeclared property's value must be: free keys, typed values, which is what
a fragment's namespaces and a schema's own `properties` are. Before it, a map of author-chosen names could only be
declared as an untyped object, which accepted anything. `SchType.additionalValueType` holds the value type; the
validator reports a failure under the entry's key (`content.home.title`), and treats every key as an entry. The
`_` and `$` prefixes that exempt a key from a record's rules mean nothing in a map, whose keys are data.

Two more apply to named types and are resolved before parsing, so `SchType` never sees them: `g-extends` (#990),
single-parent type extension, and `g-merge` (#985), how a client's alteration merges with the type it alters. Both
are described under [Traits](#extension).

None of this breaks an existing standard document; support is additive. The cost of not having it was quieter and
worse: a `oneOf` written then parsed, constrained nothing, and reported no failure. A document could already claim
to be a discriminated union and be enforcing nothing at all, which was the strongest argument for building this
rather than deferring it again. (Since issue #252 a `oneOf` without a declared discriminator is refused at parse.)

### Discriminated entries

A trait's entry type is one branch of a `oneOf`, selected by a declared discriminator. The unions are
**manufactured per gedra kind** (`entryUnionDefs`), over the traits that apply to that kind: `FormDocEntry` and
`WfDataEntry`, each with a `<Union>Unknown` default branch. A client with traits of its own gets its own copy of
each union, built over the traits it supports. Here is the shape for `formDoc`, abbreviated to the
`sample:yearly` branch:

```json
{
  "$defs": {
    "kdr.core.FormDocEntry": {
      "description": "Any entry that may be carried by a formDoc gedra, selected by its traitId.",
      "type": "object",
      "oneOf": [
        { "$ref": "#/$defs/kdr.core.NameEntry" },
        { "$ref": "#/$defs/sample.traits.ExpenseReportEntry" },
        { "$ref": "#/$defs/sample.traits.YearlyEntry" }
      ],
      "discriminator": {
        "propertyName": "traitId",
        "defaultMapping": "#/$defs/kdr.core.FormDocEntryUnknown"
      }
    },

    "sample.traits.YearlyEntry": {
      "type": "object",
      "description": "One record per year, keyed by the year.",
      "g-appliesTo": ["formDoc"],
      "properties": {
        "traitId":   { "type": "string", "const": "sample:yearly", "description": "Which kind of entry this is." },
        "data":      { "$ref": "#/$defs/sample.traits.YearlyData",
                       "description": "This entry's own data, as its trait defines it." },
        "entryId":   { "type": "string", "g-derived": true },
        "source":    { "type": "string", "g-derived": true },
        "createdAt": { "type": "string", "format": "date-time", "g-derived": true },
        "updatedAt": { "type": "string", "format": "date-time", "g-derived": true },
        "createdBy": { "type": "integer", "g-derived": true },
        "updatedBy": { "type": "integer", "g-derived": true }
      },
      "required": ["traitId", "data", "entryId", "source", "createdAt", "updatedAt", "createdBy", "updatedBy"]
    },

    "sample.traits.YearlyData": {
      "type": "object",
      "required": ["year"],
      "g-primaryKey": ["year"],
      "properties": {
        "year": { "type": "integer", "minimum": 2000, "maximum": 2100,
                  "description": "The year this record is for; the entry's primary key." },
        "note": { "type": "string", "description": "Anything recorded for the year." }
      }
    }
  }
}
```

The example leaves out the other branches, the default branch, and the descriptions of the six envelope fields.

Which makes this a valid `entries` array: two entries sharing a keyed `traitId`, and one singleton (envelope fields
left out):

```json
[
  { "traitId": "sample:yearly", "data": { "year": 2024, "note": "Filed late." } },
  { "traitId": "sample:yearly", "data": { "year": 2025 } },
  { "traitId": "kdr:name",      "data": { "name": "Site survey" } }
]
```

- **The data is its own named type.** A trait's data type is named from its entry type (`YearlyEntry` gives
  `YearlyData`, #379), so a client can alter it by name, and one alteration reaches both the entry union and the
  edit union.
- **`g-appliesTo`** on the branch names the kinds that may carry it. A trait binds to a *set* of kinds, so `kdr:name`
  is one trait on a form document and on workflow data rather than two.
- **Every branch requires the whole stored envelope.** The fields are `g-derived`, so an input projection removes
  them and a caller never sends them; on the way out every one is present.
- **Branches are closed.** A type that declares properties admits only those, and the default branch is the one
  exception, open by design (below).

**There is a parallel edit union per kind**, `FormDocEntryEdit` (`entryEditUnionDefs`), which is what a patch's
edits are typed by. Each branch, such as `sample.traits.YearlyEntryEdit`, carries the `traitId` `const`, a required
`action`, an optional `entryId`, and `data` typed by the trait's own data type but marked a fragment
(`g-optionalContents`). See [Changing a gedra: the patch](#changing-a-gedra-the-patch).

The envelope fields repeat in every branch. Branches are **flattened, not composed with `allOf`**: each branch
is a complete closed object, so closure behaves (it famously does not under `allOf`) and failures attribute to one
named branch. The duplication is in the generated document only; nothing a human writes repeats.

**`discriminator` is spelled bare, without the `g-` prefix.** This is the one exception to the rule that kd2
keywords carry the prefix, and it needs a category of its own: *borrowed from OpenAPI, used compatibly*. The
prefix exists to stop a future JSON Schema draft colliding with a name we took, and JSON Schema's answer in
this area is `propertyDependencies`, not `discriminator`. Collision with OpenAPI is the outcome we want.

Three decisions inside that:

- **`oneOf`, not `anyOf`.** Exactly one branch matches, and that is what `oneOf` says. It is also what Ajv —
  the most widely deployed implementation of `discriminator` — requires.
- **A `const` in every branch, checked at boot**: *"branch 3 of GedraEntry has no const for traitId"*. This is
  stricter than OpenAPI requires, and it is what makes a stock validator reach the same verdict we do. The
  discriminator is then an annotation on a construct that already validates correctly, not a replacement for
  validation, and its absence changes only the quality of the errors.
- **No authored `mapping`.** It duplicates what the branch `const`s already say, and a derived value written
  into the working document is a value that can drift. Synthesize it at the export boundary, from the trait
  binding.

`defaultMapping` is the fallback that makes unresolved traits opaque rather than fatal. Two things about that
branch turned out to be load-bearing, both found by building it:

- **It must not declare a `const`.** A catch-all that claims a value rejects exactly the unrecognized entry it
  exists to accept, and says so in words that read as nonsense (*"'somethingElse' is not 'opaque'"*).
- **It must be open.** A trait this reader cannot describe is one whose fields it cannot declare, so a closed
  catch-all rejects every unknown entry that carries anything — which is all of them. Closed also empties an
  entry silently on the way through, which is worse than refusing it, because nothing says it happened.

One consequence for whoever emits schema to a client: `defaultMapping` holds a **bare ref string**, not a
`{"$ref": …}` object — OpenAPI's spelling — so a walk that collects reachable types by looking for the keyword
misses the default branch entirely, and ships a union the receiving client cannot resolve.

**Why not the full OpenAPI Discriminator Object.** Its remaining machinery serves deserialization into a class
hierarchy rather than validation: branches must be `$ref`s to named schemas, mapping is by component name, and
OpenAPI's own SIG has an open discussion titled *Replace or remove discriminator*, describing implementation
as ranging "from patchy to non-existent" and proposing either `propertyDependencies` or a reduced hint over
`const` — which is what is specified above. The reduced form is where both the standard and the tooling are
heading.

### Conditional presence

A discriminator cannot express a value whose presence depends on a boolean, because the discriminated property
must be a string and the branches must be named schemas. That is `if`/`then`/`else`, and it is the most common
shape a trait's data takes. An optional choice, for example:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": ["hasValue"],
  "properties": {
    "hasValue": { "type": "boolean", "description": "Whether a choice was made." },
    "value":    { "type": "string", "description": "The chosen option.",
                  "g-options": [ { "label": "Approve", "value": "approve" },
                                 { "label": "Reject",  "value": "reject" } ] }
  },
  "if":   { "required": ["hasValue"], "properties": { "hasValue": { "const": true } } },
  "then": { "required": ["value"] },
  "else": { "not": { "required": ["value"] } }
}
```

The `if` repeats `required` deliberately: without it an absent `hasValue` makes the `const` check pass
vacuously, `then` fires, and the validator demands a value from a payload that said nothing.

Two mechanisms, then, not one — a union across traits, and a conditional within a trait. They share only their
consequence for the form engine, which must re-render when the watched field changes.

### `g-primaryKey`

**Built** (#487). Declared **on the data type**, as an ordered array of property names, alongside `required`:

```json
{
  "type": "object",
  "required": ["year"],
  "g-primaryKey": ["year"]
}
```

On the type rather than the property, for three reasons: it follows the layer's existing required-is-on-the-side
convention; a composite key is ordered, which a per-property boolean cannot express; and it is how SQL says the
same thing. It goes on the *data* type, since that is the half a trait owns. It travels as the `primaryKey`
parameter of a trait declaration, and as the `primaryKey` field of a stored `traitDef`.

**Absence is meaningful**: no key means at most one entry with that `traitId` on a gedra. One keyword covers
both the singleton and the keyed-collection cases.

**Checked when the trait is declared** (`traitEntryOf` in `GedraEntry.kt`), where the author is:

- every named property exists and is `required` (an optional key means two entries omitting it collide);
- every key property is scalar;
- no key property is `g-derived`, since identity that depends on a computation re-partitions stored data whenever
  the computation changes;
- a keyed trait declares its data **inline**, not as a `$ref`: the key and the fields it names have to be one type,
  and a `$ref` is resolved only when types compile.

Uniqueness is scoped to `(gedra, traitId)` and checked on every write (`checkEntryKeys`). It is compared on
**coerced** values: `"2024"` and `2024` are one key, so the check runs after coercion or duplicates slip through
looking distinct.

The keyword also tells the form engine whether a trait renders as a collection with an add-row or as a single
section — a use that does not vary by surface, so it stays a typed attribute on `SchType` rather than moving
into the layout vocabulary.

### `g-derived`

Marks a property the client does not supply. Accepts either form from the start, so that widening it later is
not a migration:

- **`true`** — something else produces this value. The *what*.
- **an object** — and here is how, once there is a language to say it in.

```json
{
  "properties": {
    "gallonsPerCan": { "type": "number", "description": "Capacity of one can." },
    "canCount":      { "type": "integer", "description": "How many cans." },
    "totalGallons":  { "type": "number", "g-derived": true, "description": "Total capacity." }
  }
}
```

**Code-computed values are built** (#712), and are computed **on read**, never stored. A component registers a
`GedraDataDeriver` bound to a trait id, and the boot requires that trait to be global, since a client's bare id
may be another client's too. The deriver fills its fields onto the entries a read surface presents; nothing is
stored, so there is no column, no migration, and no stored copy to go stale. A derived value is for display only:
it never takes part in validation or in whether a form is complete. The schema declares the field's status and the
code supplies the value, and neither needs to know about the other.

The object form, for derivations a user authors, is still intent. So are *interactive* derivations, computed in the
form as the user types; both wait on a script language. See [Deferred](#deferred).

## Projections

One authored definition yields several schema shapes, and the derived ones are **generated, never authored**,
or they drift:

- **Input** — `traitId` and `data`. `source` is deduced from context. `origin`, once built, may be supplied only by
  an internal integration or an admin. `g-derived` properties are removed.
- **Admin input** — as above, with the fields an admin may set.
- **Output** — everything.
- **Edit** — the patch's edit union, built from the same trait as the entry union rather than from it.

This is the third mechanism in the design that derives one document from another, alongside **export** (same
audience, different dialect) and **overlay** (same audience, narrowed by scope). All three will eventually
apply at once — the input schema of a client-narrowed trait, exported for third-party tooling — so the order
is fixed: **overlay, then project, then export.** Overlay decides what the effective type is, projection takes
an audience's slice of it, export changes dialect last.

A projection reads the schema *and* the trait wrapper — `g-derived` lives in the schema, admin-only lives in
the wrapper — so the projector belongs in the Gedra tier and consumes the kernel `Sch*` layer rather than
living inside it.

### Removed is not the same as forbidden

Entry types are closed, and read-modify-write — fetch the output shape, change one field, send it back — is
how every form works. So clients will send fields the input shape does not declare, and the policy differs by
the reason the field was removed:

- **Derived and context-deduced fields (`g-derived`, `source`): stripped silently.** Echoing them is not an
  error; they are simply not the client's business, and rejecting makes the natural client pattern impossible.
- **Privileged fields (`origin` from a non-admin, once built): rejected loudly.** Silently dropping a field the
  caller lacks the privilege to set hides exactly the case worth seeing in a log.

The projection carries `required` along with the fields, or a derived-and-required property becomes a missing
required property on something the client may not send.

## What lives in code rather than schema

Annotations are partitioned by consumer: validator-read and surface-invariant goes on `SchType` /
`SchProperty`; anything that varies by caller or surface does not. `g-derived` is derived for everyone, so it
is an attribute. "Editable only by admins" varies by caller, so it is a directive on the trait.

These are enforced outside the schema, each with its own clear error:

- **`origin` is immutable after creation** and editable only by an admin (intent, with `origin` itself). JSON
  Schema cannot say this; `readOnly` means "server-owned, do not send," which is a different claim.
- **A trait is bound to the gedra kinds it may be stored on** (built): `g-appliesTo` decides which kind's unions
  carry its branch, so an entry on any other kind is refused.
- **Definitions may not collide** (built): global trait ids are rooted and client ids bare (#807, #951), and a
  client's new types live in its own namespace (#949).
- **A definition may not change in a way that breaks access to stored data** (built as a publish report; see below).

The first two are constraints on a single field or type, and if more of that kind appear they are probably a
keyword rather than a growing list of hand-written checks. The last two are structural and belong in code
whatever happens.

### What "does not break stored data" means

**Built as a publish impact report** (`ConfigImpact`, #935). When a published-only client publishes one of its
stored configurations, each of its live rows is judged twice, under the configuration it runs now and under the
candidate, and what the candidate breaks is reported: a trait the client would no longer support, an entry that no
longer validates, a form whose workflow would be gone, or a recorded state naming a task the workflow would no
longer define. A publish with findings is refused unless the caller acknowledges the report. A client on the latest
tier already runs every revision as it is written, so its report is always empty.

The report judges the data that is there. The rules below say, ahead of any data, which edits it will find, and
remain intent as static checks. Four rules, the first two counterintuitive in this layer:

- Adding a **required** property is safe **if it has a `default`** — the validator injects defaults for missing
  required properties, so existing entries still validate. Without one it breaks.
- **Removing** a property breaks, because entry types are closed: the value left in stored data becomes an
  undeclared property.
- Widening a type, relaxing a bound, adding a `g-options` entry: safe. Narrowing anything: breaks.
- **Changing `g-primaryKey` is never safe in place.** It does not merely affect validation, it re-partitions
  existing entries — drop a component and two entries that were distinct become duplicates, in data already
  written. No version pin helps, because the collision is between rows rather than within one.

A version pinned on the entry would turn most of the rest from a prohibition into a choice: compatible edits in
place, anything else minting a new version, old entries validating against the version they were written
under. The cost is retaining every version's schema indefinitely and readers spanning several. That trade is
attractive and not yet taken — see [Open questions](#open-questions).

## Changing a gedra: the patch

A stored gedra is changed by a **patch**, not an update. An update replaces the document, so whatever it leaves out
is deleted; a patch says what to do with each entry it names and leaves every other entry alone.

`POST /gedra/patch` (#337) carries it. Each client whose schema varies from the global one also gets a copy at
`/gedra/<client>/patch` (#387), typed by that client's traits. A workflow task's save, `/gedra/workflow/save`
(#535), applies its edits through the same patch. A stored configuration bundle is edited slot by slot with the same
three actions, at `/clientAdmin/config/bundle/patch` and `/admin/client/config/bundle/patch` (#732).

```json
{
  "targets": {
    "formDoc": [
      {
        "gedraId": "gd.fd.acme.e20260812130405123AbCd",
        "edits": [
          { "action": "addOrReplace", "traitId": "kdr:name", "data": { "name": "2024 site survey" } },
          { "action": "addOrMerge", "traitId": "sample:questionnaire", "data": { "hasIssue": false } },
          { "action": "deleteOrNoOp", "traitId": "sample:yearly", "data": { "year": 2023 } }
        ]
      }
    ]
  }
}
```

- **A target is one gedra**, grouped under its kind. Only `formDoc` is offered, since nothing yet creates a gedra of
  another kind. The kind is stated twice, in the group and in the id, because a schema cannot read a prefix out of
  an id to type the edits; a target whose id disagrees with its group is refused.
- **An edit is a branch of the kind's edit union** (`FormDocEntryEdit`), selected by its `traitId`. The verb sits
  beside the trait rather than inside an entry, because an action is not a property of a value.
- **The entry an edit names is its address**: the trait, plus the key values carried in the edit's own `data` for a
  keyed trait. So a delete of a keyed entry sends its key and nothing else.
- **`data` is validated as a fragment** on the way in (`g-optionalContents`), and the assembled entry is validated
  in full, `required` included, once the edit has been folded over what is stored.

| `action` | entry present | entry absent |
|---|---|---|
| `deleteOrNoOp` | removed | nothing; not an error |
| `addOrMerge` | the supplied keys replace those stored; the others are kept | created from the supplied data |
| `addOrReplace` | replaced whole | created |

`action` is required, with no default. `data` may be absent only for a delete, and absent data never means delete:
the verb does, because a primary key lives in `data`.

### Rules and options

| rule or option | what it does |
|---|---|
| one client per patch | A patch naming gedras of more than one client is refused (#356). A client's own endpoint also refuses a gedra of another client. |
| no duplicate addresses | Two edits in one target may not name the same entry: the same unkeyed trait, or the same key of a keyed one. |
| `allowAdditionalTraits` (request) | Without it, an edit that stores a trait the client does not support is refused (#379). A delete is always allowed, so an unsupported entry can be cleaned up. |
| `entryId` (edit) | A staleness check, never a selector: it must match the entry the address resolves to, or the edit is refused as written against an out-of-date copy. A delete naming an entry that is already gone is a no-op. |
| no-op | An edit that leaves an entry's data as stored is reported `applied: false`, and the entry's `updated` stamps do not move (#626). |
| `g-visibleWhen` gates | A field hidden from the caller keeps its stored value. A change to it, or a delete that would take it away, is refused (#830). |
| `overrideReason` (request) | Asks to override the workflow trait locks the edits touch, for a caller a lock allows to override. The reason is recorded with the write (#857). |
| `underLock` (service) | A check a calling service supplies, run on the gedra and its state under the lock before any edit (#856). The workflow save uses it; it is not a request field. |

The answer is one result per target: `{"gedraId": …, "outcomes": [{"traitId": …, "applied": true}]}`.

### How a patch runs

**First, admit every target before writing any.** For each target: its kind matches its id; no two edits share an
address; the gedra exists and the caller's read scope reaches it, or the answer is a 404 naming only the id, so
absent and out of reach look alike; every trait it stores is supported, unless `allowAdditionalTraits` is set; and
the trait's save-time functions prepare each edit's data (#728). A refusal here refuses the whole call, and nothing
is written.

**Then apply each target in its own transaction**, in a kind order the server chooses (`kindApplyOrder`), not the
request's. Under the gedra's lock:

1. the row is read again, since a merge must work from what is stored now;
2. the caller's `underLock` check runs;
3. each edit is applied by its address, with the staleness and no-op rules and the `g-visibleWhen` gates;
4. the write guards judge the traits the edits actually change (a trait lock refuses with a 409 naming the trait
   and its workflow);
5. the assembled entries are validated, and written;
6. any state a guard asked to record, such as an override's trail, is written;
7. the post-write hooks run, so the gedra's derived state follows the edit (#675).

Trait locks are checked under the lock, not at admission, because whether a lock holds depends on the form's
workflow state, and which traits a patch changes is known only once its edits are folded over what is stored.

**Atomicity is per target.** A refusal in the apply phase fails only that target, and the targets before it have
already committed. Recovery is by replay, which is safe because every edit is idempotent by value: a retry
re-applies what already landed and changes nothing but the audit stamps. Issue #1307 tracks making such a refusal
say which target it was and what landed. A pre-check of the apply phase is deferred; see
[When batch editing of form documents becomes common](../../development/deferred-work.md#when-batch-editing-of-form-documents-becomes-common).

**Process-owned data is state**, held in state traits ([`states.md`](states.md)), which a patch's edits do not
address. The one way a patch writes state is through a write guard, which may record state with the write, as a lock
override records itself on the workflow's trail.
Admin-only entries are not enforced yet: that needs a directive on the trait, which does not exist.

### Every write runs the same way

Every path that writes gedra data (create, import, patch, and the workflow save) admits and prepares its input,
applies it under the gedra's lock, validates the result, writes it, and then lets the gedra's state follow. Values
a trait derives for display are computed on read and never stored; state derived from the data is recomputed in
the same transaction as the write.

A patch does **hard** validation only: the schema's, and a failure writes nothing. What a workflow requires of a
form is **soft**: it never fails the write. A questionnaire trait marks its answers optional so a page can be saved
half-filled; the workflow says which traits a task needs, and a workflow save that leaves one unmet answers
`saved: false` naming it, as a result rather than an error. Making the trait's fields required instead would turn a
soft rule into a hard one and break the partial save.

### A POST, not an HTTP PATCH

`HttpMethod` has `DELETE`, and the form delete is `DELETE /gedra/formDoc`, which targets one resource and means
exactly what DELETE means. The patch stays a POST. It targets a set of gedras named in its body, not the resource
at its URI, and the PATCH method advertises body formats this design does not use: JSON Patch (RFC 6902) is an op
array, and JSON Merge Patch (RFC 7386) says `null` deletes a member, where here absent data must never mean delete.

## Tasks declare what is owed

**An entry is present or absent; its data is not what makes it present.** A present entry with empty data (`{}`)
satisfies a task that requires its trait -- a trait whose payload is legitimately empty must be able to meet a
gate -- and the form page stores one for every trait of a task the user saves, touched or not. What a trait needs
of its *content* is said by its schema's required fields, which a save refuses to leave unmet. "Not yet answered"
is therefore never a state of the entry: either no entry exists, or one does and holds what was answered.

Instead a workflow **task** declares what it expects. Today that is which `traitId`s, each required or not
(`WfTraitRef`). Missing data is then a diff between what a task expects and what the gedra holds, which is what the
GUI reports.

The intent goes further: a task would also name which primary keys it expects, including a range, such as the
years 2020 through 2024. That expectation language would be its own small vocabulary (traits, keys, and ranges
over key values), and it is a requirement spec, not JSON Schema, which has no home for a range over a key. It
should not be smuggled into the schema layer.

A task is also one screen of data entry, so the rest of its definition is layout: title, layout style, and
bindings to specific React components where a trait needs a custom experience or carries an integration.

Genuine optionality within a form — a radio group the user may leave unanswered — is the
[conditional](#conditional-presence) shape, and is unrelated to whether a task has been completed.

## Open questions

- **Does an entry pin a trait version?** Attractive, and the enabler for the compatible-edit rules above.
- **May a trait extension replace an inherited behavior, or only chain after it?** Chain-only is the reversible
  default.
- **Do `source` transitions need an audit trail before general history exists?** An approval flipping `source`
  across a set of traits is an audit event, and `updatedBy` / `updatedAt` hold the last change rather than
  the sequence. Either the first release carries a narrow transition log or approvals are unauditable and that
  is stated.

## Deferred

Recorded here as considered, not overlooked; promoted to
[`docs/development/deferred-work.md`](../../development/deferred-work.md) as their triggers become concrete.

- **Attribute-level and role-conditional editability.** "Editable only by admins" is a predicate on the
  caller, not the boolean that splits into server-enforced immutability and presented-read-only. Deferring is
  safe as long as such directives stay on the trait and out of the schema document. `origin`'s admin-only
  rule is an instance of the general mechanism and should not be hand-written in a shape that mechanism cannot
  later absorb.
- **Interactive and user-authored derived values.** Gallons per can times number of cans, computed in the form as
  the user types, or written by a user rather than in code. These need the minimal script language, and by
  necessity a KMP-shared one, since the same expression runs in the form and on the server. A code-computed value is
  already computed on read and never stored (#712); if a script-authored one were ever stored instead, the write
  path would have to recompute it rather than accept it, since a client that can send a derived value
  independently of its inputs can lie.
- **Real history.** Substantially more than revision rows, and expected after the demo stage.
- **The rest of the trait wrapper.** Permission directives beyond the schema binding, such as admin-only entries.
- **The export contract's one gap.** `g-primaryKey` has no standard equivalent — `uniqueItems` is whole-item
  equality, and JSON Schema's `uniqueKeys` proposal never landed — so stripping it on export makes the export
  *looser* than us, against the rule that an inexact export is stricter and never looser. Accepted: the
  constraint governs our own stored entries, which no third-party validator is checking.
