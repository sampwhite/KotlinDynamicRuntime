# The Gedra Entry

The design of the universal stored entity and the schema constructs it needs. This is a **declaration of
intent**, written before the code: none of it is implemented yet, and issue #240 tracks both this document and
the schema work it calls for.

It is a companion to [`code-guide.md`](code-guide.md) — that file says how we write code, this one says what
one particular subsystem is — and to [`deferred-work.md`](deferred-work.md), which holds the items named here
as deliberately postponed.

Decisions are stated plainly. Where something is genuinely unsettled it appears under
[Open questions](#open-questions) rather than being papered over, and where something was considered and
postponed it says so, so a later reader can tell a deliberate omission from an oversight.

## Vocabulary

- **Gedra** — the universal top-level entity: a vessel that carries any cargo. Also the product's brand name,
  on the Jira model where the brand and the colloquial unit name are the same word. Hard G. Domain-model tier,
  so no `Kdr` prefix.
- **Gedras** — the root table, one row per gedra, and the transaction boundary. **GedraRow** is one row;
  **Gedra** is the richer aggregate (the root plus its entries).
- **Gedra kinds** — what a gedra *is*: `userData`, `formDoc`, `wfDef`, `wfData`, `fileRef`.
- **GedraEntry** — one schema-defined unit stored on a gedra. A gedra holds many, usually of differing kinds.
- **Trait** — the definition a `GedraEntry` is an instance of. It binds a schema plus functional and
  permission directives, and is identified by a **`traitId`**.

A gedra is a composition of traits; an entry is one trait's worth of data.

## The shape of an entry

```json
{
  "traitId": "expenseReport",
  "data": { "year": 2024, "totalAmount": 4820.15 },
  "source": "user",
  "origin": { "channel": "excel", "fileName": "xxx.xlsx", "fileRef": "///somepath/xxx.xlsx" },
  "createdBy": 232,
  "modifiedBy": 265,
  "createdAt": "2020-01-05T06:02:03.256Z",
  "modifiedAt": "2023-03-01T04:02:03.566Z",
  "lockedBy": ["wfId1", "wfId2"]
}
```

- **`traitId`** — which trait this entry carries. It is the discriminator: it decides the shape of `data`. The
  `Id` suffix is load-bearing, because traits are authored at runtime and a bare `type` would read like a
  closed set the code knows about.
- **`data`** — the trait's payload, and the only part whose shape varies.
- **`source`** — **mutable**. Who is accountable for the *current* value: `excel` while it remains an
  unreviewed suggestion, `user` once a human has edited it or approved it. Always present; a direct endpoint
  call sets `user`.
- **`origin`** — **immutable**. How the entry arrived, recorded once at creation and never rewritten:
  `channel` plus whatever detail that channel carries. Absent when the entry was created by a direct endpoint
  call.
- **`createdBy` / `modifiedBy` / `createdAt` / `modifiedAt`** — per entry, not per gedra.
- **`lockedBy`** — workflow ids currently holding the entry.

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
- **A stable opaque entry id carries identity**, and is what references, revision rows and error paths use.

This is one instance of a rule the design applies in three places — a stored reference points at a stable
surrogate, while the meaningful name is a lookup key and a label. The others are `traitId` (whose qualified
name changes if a definition is promoted between scopes) and any future reference between entries.

## Traits

A `Trait` binds a `traitId` to a schema, together with functional and permission directives — pre-processors,
who may edit what, which gedra kinds it may be stored on. Only the schema binding is designed here; the rest
of the wrapper is out of scope.

### The binding is explicit

A `traitId` names its schema explicitly. Deriving one from the other by naming convention is rejected: the
convention holds right up until a client names something badly, and by then stored data depends on it.

This is the same principle as declaring the discriminator rather than deducing it, and as the surrogate id
above — the system records intent instead of re-deriving it.

It also means the branch `const` values in the generated schema (below) come *from* the binding table rather
than being written alongside it, so there is one authority and nothing to drift.

### Scopes

Traits live in three pools, the narrowest scope winning where they overlap:

1. **Global** — shipped with the product.
2. **Client** — a client admin may create wholly new traits, and may augment or extend global ones.
3. **Workflow** — a workflow definition brings in the traits relevant to it and distributes them across tasks,
   augmenting or extending as needed.

Traits introduced by a client or a workflow are **namespaced by `clientId`**, which the schema layer already
supports: refs are flat and dotted, so a qualified `traitId` needs no new mechanism.

### Augment or extend

Two moves, and which one applies follows from **storage identity**:

- **Augment (overlay)** — same `traitId`, therefore shared storage, therefore **narrowing only**. Usually
  layout.
- **Extend** — a new `traitId`, therefore separate storage, therefore broadening is legitimate.

**Narrowing binds the write path, never the read path.** Two workflows may narrow one trait differently while
sharing its storage; if reads validated against the narrowed view, a workflow would reject data another
workflow legitimately wrote. Read-time validation uses the base definition. That is *why* narrowing-only is
safe: the base accepts a superset of anything an overlay could have written, so no overlay can produce data the
base cannot read back.

**Overlays may not touch behavior.** Narrowing a schema is safe because the base still accepts the result;
there is no equivalent guarantee for code. If one workflow could swap a pre-processor while another could not,
two workflows would write differently-processed data under one `traitId` and the base definition would no
longer describe what is stored. Behavior changes require an extension.

Narrowing is checked against a closed list of overlayable attributes rather than inferred, and overlays merge
**raw documents, re-parsed** — never parsed objects.

### Extension is a live link

An extension records its base, and the link is live rather than a copy taken at authoring time: an extension
inherits its base's pre-processor and other behavior, and a copy would leave every extension stranded on the
version of the behavior that existed when it was made.

The cost is that a change to a base propagates schema *and* code into every descendant, so the compatibility
checker needs the descendant graph. That is the main reason the edge is stored rather than merely performed.

### Two populations

The global pool is not homogeneous:

- **Code-backed traits** ship with a release and carry real functionality — a DocuSign integration and its
  kind. A client cannot author one, and extending one may not violate the assumptions its code makes.
- **Data-authored traits** are pure definition, created by clients and workflows.

### Unresolved traits

A `traitId` that does not resolve is **opaque, never an error, and never dropped on write-back**: its `data` is
carried as a plain map. Code that reads entries is generally handed a limited set of trait-to-type bindings and
ignores what it does not recognize, which is what lets a query span clients whose definitions it has never
seen.

An unresolved **pre-processor** is the opposite case and must fail loudly. Unknown schema means some data
cannot be interpreted; unknown behavior means data would be stored looking processed when it is not.

## Schema constructs

Four things the `Sch*` layer does not have yet. `SchParser` currently rejects `oneOf` and `if` outright, and
`SchType` has no variant representation, so all of this is net-new.

### Discriminated entries

A `Trait`'s entry type is one branch of a `oneOf`, selected by a declared discriminator:

```json
{
  "$defs": {
    "gedra.GedraEntry": {
      "description": "One schema-defined unit stored in a Gedra.",
      "oneOf": [
        { "$ref": "#/$defs/gedra.ExpenseReportEntry" },
        { "$ref": "#/$defs/gedra.ApprovalChoiceEntry" }
      ],
      "discriminator": {
        "propertyName": "traitId",
        "defaultMapping": "#/$defs/gedra.OpaqueEntry"
      }
    },

    "gedra.ExpenseReportEntry": {
      "type": "object",
      "additionalProperties": false,
      "required": ["traitId", "data", "source"],
      "properties": {
        "traitId": { "type": "string", "const": "expenseReport",
                     "description": "Identifies which trait this entry carries." },
        "data":    { "$ref": "#/$defs/gedra.ExpenseReportData" },
        "source":  { "type": "string", "description": "Who is accountable for the current value." },
        "origin":  { "$ref": "#/$defs/gedra.Origin" }
      }
    },

    "gedra.ExpenseReportData": {
      "type": "object",
      "additionalProperties": false,
      "required": ["year", "totalAmount"],
      "g-primaryKey": ["year"],
      "properties": {
        "year":        { "type": "integer", "minimum": 2000,
                         "description": "Reporting year this report covers." },
        "totalAmount": { "type": "number",
                         "description": "Total claimed, in the client's currency." },
        "notes":       { "type": "string", "description": "Free-text explanation." }
      }
    }
  }
}
```

Which makes this a valid `entries` array — two entries sharing a `traitId`, one singleton:

```json
[
  { "traitId": "expenseReport",   "source": "excel", "data": { "year": 2024, "totalAmount": 4820.15 } },
  { "traitId": "expenseReport",   "source": "user",  "data": { "year": 2025, "totalAmount": 1190.00 } },
  { "traitId": "managerApproval", "source": "user",  "data": { "hasValue": true, "value": "approve" } }
]
```

`ApprovalChoiceEntry` is omitted above: it repeats the same envelope, with `data` pointing at
`gedra.OptionalChoice` from [Conditional presence](#conditional-presence).

The envelope fields repeat in every branch. Branches are **flattened, not composed with `allOf`**: each branch
is a complete closed object, so `additionalProperties: false` behaves (it famously does not under `allOf`) and
failures attribute to one named branch. The duplication is in the generated document only — nothing a human
writes repeats.

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
  binding table.

`defaultMapping` is the fallback that makes unresolved traits opaque rather than fatal.

**Why not the full OpenAPI Discriminator Object.** Its remaining machinery serves deserialization into a class
hierarchy rather than validation: branches must be `$ref`s to named schemas, mapping is by component name, and
OpenAPI's own SIG has an open discussion titled *Replace or remove discriminator*, describing implementation
as ranging "from patchy to non-existent" and proposing either `propertyDependencies` or a reduced hint over
`const` — which is what is specified above. The reduced form is where both the standard and the tooling are
heading.

### Conditional presence

A discriminator cannot express a value whose presence depends on a boolean, because the discriminated property
must be a string and the branches must be named schemas. That is `if`/`then`/`else`, and it is the most common
shape a trait's data takes. The `gedra.OptionalChoice` type:

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

Declared **on the type**, as an ordered array of property names, alongside `required`:

```json
{
  "type": "object",
  "required": ["year", "totalAmount"],
  "g-primaryKey": ["year"]
}
```

On the type rather than the property, for three reasons: it follows the layer's existing required-is-on-the-side
convention; a composite key is ordered, which a per-property boolean cannot express; and it is how SQL says the
same thing. It goes on the *data* type, since that is the half a trait owns.

**Absence is meaningful**: no key means at most one entry with that `traitId` on a gedra. One keyword covers
both the singleton and the keyed-collection cases.

Checked at boot: every named property exists and is `required` (an optional key means two entries omitting it
collide), and every key property is scalar. Uniqueness is scoped to `(gedra, traitId)`, and compared on
**coerced** values — `"2024"` and `2024` are one key, so the check runs after coercion or duplicates slip
through looking distinct.

A key property may not be `g-derived`: identity that depends on a computation re-partitions stored data
whenever the computation changes.

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

Code-backed traits will use the boolean indefinitely, because their values are computed by a pre-processor
before storage; the object form is for derivations a user authors. The schema declares the field's status and
the code supplies the value, and neither needs to know about the other.

An entry's *interactive* derivations — gallons per can times number of cans — are deferred along with the
script language they need. See [Deferred](#deferred).

## Projections

One authored definition yields several schema shapes, and the derived ones are **generated, never authored**,
or they drift:

- **Input** — `traitId` and `data`. `source` is deduced from context. `origin` may be supplied only by an
  internal integration or an admin. `g-derived` properties are removed.
- **Admin input** — as above, with the fields an admin may set.
- **Output** — everything.

This is the third mechanism in the design that derives one document from another, alongside **export** (same
audience, different dialect) and **overlay** (same audience, narrowed by scope). All three will eventually
apply at once — the input schema of a workflow-narrowed trait, exported for third-party tooling — so the order
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
- **Privileged fields (`origin` from a non-admin): rejected loudly.** Silently dropping a field the caller
  lacks the privilege to set hides exactly the case worth seeing in a log.

The projection carries `required` along with the fields, or a derived-and-required property becomes a missing
required property on something the client may not send.

## What lives in code rather than schema

Annotations are partitioned by consumer: validator-read and surface-invariant goes on `SchType` /
`SchProperty`; anything that varies by caller or surface does not. `g-derived` is derived for everyone, so it
is an attribute. "Editable only by admins" varies by caller, so it is a directive on the `Trait`.

These are enforced in the write path, each with its own clear error:

- **`origin` is immutable after creation** and editable only by an admin. JSON Schema cannot say this;
  `readOnly` means "server-owned, do not send," which is a different claim.
- **A trait is bound to the gedra kinds it may be stored on** — a cross-document constraint no schema keyword
  reaches.
- **Clients and workflows may not create colliding definitions.**
- **A definition may not change in a way that breaks access to stored data.**

The first two are constraints on a single field or type, and if more of that kind appear they are probably a
keyword rather than a growing list of hand-written checks. The last two are structural and belong in code
whatever happens.

### What "does not break stored data" means

Four rules, the first two counterintuitive in this layer:

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

## Tasks declare what is owed

**An entry with no data does not exist.** Nothing is created as a placeholder, and "not yet answered" is never
a state of the entry.

Instead a workflow **task** declares what it expects: which `traitId`s, and optionally which primary keys —
including a range, such as the years 2020 through 2024. Missing data is then a diff between what a task expects
and what the gedra holds, which is what the GUI reports.

That expectation language is its own small vocabulary — traits, keys, and ranges over key values — and it is a
requirement spec, not JSON Schema, which has no home for a range over a key. It should not be smuggled into the
schema layer.

A task is also one screen of data entry, so the rest of its definition is layout: title, layout style, and
bindings to specific React components where a trait needs a custom experience or carries an integration.

Genuine optionality within a form — a radio group the user may leave unanswered — is the
[conditional](#conditional-presence) shape, and is unrelated to whether a task has been completed.

## Open questions

- **Is `entries` an array in the gedra document, or rows in a sub-table?** Everything above is a wire shape and
  holds either way, but primary-key uniqueness (application check against unique index), per-entry audit
  (fields against columns) and locking all read differently under the two.
- **Is the payload named `data`?** Used throughout this document, replacing `input`. `input` describes the
  direction of one journey and stops being true once the value is stored and read back; `value` collides with
  the inner `value` of the conditional shape.
- **Does an entry pin a trait version?** Attractive, and the enabler for the compatible-edit rules above.
- **How is an entry addressed?** `entries[3].data.year` is index-based and shifts on insert;
  `entries[expenseReport:2024].data.year` survives. Validation failure paths, form-engine keys, attribute-level
  permissions and derived-value dependencies all consume the same vocabulary, three of them deferred — which is
  the argument for settling it while only the first exists.
- **May a trait extend more than one base, and may an extension replace an inherited pre-processor or only
  chain after it?** Single-parent and chain-only are the reversible defaults.
- **Is a trait bound to one gedra kind or a set?** Widening later is easy and narrowing is not.
- **Do `source` transitions need an audit trail before general history exists?** An approval flipping `source`
  across a set of traits is an audit event, and `modifiedBy` / `modifiedAt` hold the last change rather than
  the sequence. Either the first release carries a narrow transition log or approvals are unauditable and that
  is stated.
- **Is `Trait` the wrapper's name?** Adopted here. It fits better than `GedraEntryDef` because the wrapper is
  more than a schema. One irony to note: if extension is single-parent this is class inheritance, and "trait"
  is the word for the other thing.

## Deferred

Recorded here as considered, not overlooked; promoted to [`deferred-work.md`](deferred-work.md) as their
triggers become concrete.

- **Attribute-level and role-conditional editability.** "Editable only by admins" is a predicate on the
  caller, not the boolean that splits into server-enforced immutability and presented-read-only. Deferring is
  safe as long as such directives stay on the `Trait` and out of the schema document. `origin`'s admin-only
  rule is an instance of the general mechanism and should not be hand-written in a shape that mechanism cannot
  later absorb.
- **Interactive derived values.** Gallons per can times number of cans. These need the minimal script language,
  and by necessity a KMP-shared one, since the same expression runs in the form as the user types and on the
  server when it is saved. Two questions to answer then and not now: whether the value is stored or computed on
  read, and if stored, whether the write path recomputes or merely accepts — a client that can send a derived
  value independently of its inputs can lie. Both are read-only-adjacent, so this and the item above want to
  land together.
- **Real history.** Substantially more than revision rows, and expected after the demo stage.
- **The rest of the `Trait` wrapper.** Functional and permission directives beyond the schema binding.
- **The export contract's one gap.** `g-primaryKey` has no standard equivalent — `uniqueItems` is whole-item
  equality, and JSON Schema's `uniqueKeys` proposal never landed — so stripping it on export makes the export
  *looser* than us, against the rule that an inexact export is stricter and never looser. Accepted: the
  constraint governs our own stored entries, which no third-party validator is checking.
