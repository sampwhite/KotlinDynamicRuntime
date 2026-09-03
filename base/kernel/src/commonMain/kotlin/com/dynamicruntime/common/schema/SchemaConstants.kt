package com.dynamicruntime.common.schema

// String constants for the standard JSON Schema (draft 2020-12) keywords and the
// `type` keyword's values. Per the code guide: lowerCamelCase const vals in
// upper-cased acronym objects, referenced qualified, with the const-naming
// inspection suppressed at the object level. Each plain keyword's NAME matches
// its VALUE. Three variant rules:
//   * a leading `$` becomes a `d` prefix with the next letter capitalized
//     (`$ref` -> dRef) (issue #2);
//   * a name colliding with a Kotlin hard keyword takes a `k` prefix with the
//     next letter capitalized (`if` -> kIf; `then` included for consistency)
//     (issue #2);
//   * a kd2-specific keyword's VALUE carries a `g-` prefix that its NAME does
//     not (allowCoerce -> "g-allowCoerce"), so call sites read unchanged. See
//     SCH.gPrefix for why the prefix exists (issue #194).

/** JSON Schema keywords (the object keys). */
@Suppress("ConstPropertyName", "unused")
object SCH {
    // Core / identifier ($-keywords -> d prefix).
    const val dSchema = $$"$schema"
    const val dId = $$"$id"
    const val dRef = $$"$ref"
    const val dDefs = $$"$defs"
    const val dAnchor = $$"$anchor"
    const val dDynamicRef = $$"$dynamicRef"
    const val dDynamicAnchor = $$"$dynamicAnchor"
    const val dVocabulary = $$"$vocabulary"
    const val dComment = $$"$comment"

    // Annotations / metadata.
    /**
     * Standard JSON Schema `title`: a human label for a field, as against [description]'s longer explanation.
     *
     * Not parsed into `SchType` yet. When it is, the substitution has to be **per surface, not global**. A form
     * asking a person to fill something in wants the title. A surface that documents the wire wants the *key*:
     * the endpoint catalog, where the key is the payload's real field name and what you need to write a call
     * or read an error path like `input.contacts[1].handle`; and an editor for a stored definition, where the
     * field names are themselves the contract being authored. Substituting titles there would make those
     * surfaces worse at their job, so whoever adds label support should scope it deliberately.
     */
    const val title = "title"
    const val description = "description"
    const val default = "default"
    const val examples = "examples"
    const val deprecated = "deprecated"
    const val readOnly = "readOnly"
    const val writeOnly = "writeOnly"

    // Generic validation.
    const val type = "type"
    const val enum = "enum"
    const val const = "const"

    // Objects.
    const val properties = "properties"
    const val patternProperties = "patternProperties"
    const val additionalProperties = "additionalProperties"
    const val unevaluatedProperties = "unevaluatedProperties"
    const val required = "required"
    const val propertyNames = "propertyNames"
    const val minProperties = "minProperties"
    const val maxProperties = "maxProperties"
    const val dependentRequired = "dependentRequired"
    const val dependentSchemas = "dependentSchemas"

    // Arrays.
    const val prefixItems = "prefixItems"
    const val items = "items"
    const val unevaluatedItems = "unevaluatedItems"
    const val contains = "contains"
    const val minContains = "minContains"
    const val maxContains = "maxContains"
    const val minItems = "minItems"
    const val maxItems = "maxItems"
    const val uniqueItems = "uniqueItems"

    // Strings.
    const val minLength = "minLength"
    const val maxLength = "maxLength"
    const val pattern = "pattern"
    const val format = "format"

    // Numbers.
    const val minimum = "minimum"
    const val maximum = "maximum"
    const val exclusiveMinimum = "exclusiveMinimum"
    const val exclusiveMaximum = "exclusiveMaximum"
    const val multipleOf = "multipleOf"

    // Combinators / conditionals (if/then/else collide with Kotlin keywords -> k prefix).
    const val allOf = "allOf"
    const val anyOf = "anyOf"
    const val oneOf = "oneOf"
    const val not = "not"
    const val kIf = "if"
    const val kThen = "then"
    const val kElse = "else"

    /**
     * OpenAPI's `discriminator`, sitting beside [oneOf] and naming the property that selects the branch
     * (issue #252). Spelled **bare**, and it is the one keyword of ours that is: see [gPrefix] for the rule
     * this breaks and why.
     *
     * The reduced form only — [propertyName] and [defaultMapping]. OpenAPI's remaining machinery serves
     * deserialization into a class hierarchy rather than validation, and its own maintainers have an open
     * proposal to replace or remove the object; `mapping` in particular duplicates what the branches' [const]
     * values already say, so it is synthesized at the export boundary rather than authored here.
     */
    const val discriminator = "discriminator"

    /** Inside [discriminator]: the name of the property whose value selects the branch. */
    const val propertyName = "propertyName"

    /**
     * Inside [discriminator]: where a value naming no branch goes (OpenAPI 3.2). A `$ref` to the branch that
     * accepts what this reader does not recognize; absent means an unrecognized value is a failure.
     */
    const val defaultMapping = "defaultMapping"

    // Content.
    const val contentEncoding = "contentEncoding"
    const val contentMediaType = "contentMediaType"
    const val contentSchema = "contentSchema"

    // Custom (kd2) keywords — not part of standard JSON Schema. Their VALUES carry the `g-` prefix; their
    // Kotlin names do not (see the file header).
    /**
     * The prefix marking a keyword as **kd2's own**, for Gedra/Gyassa. Our keywords sit in the same namespace
     * JSON Schema uses for its own, so the prefix is what stops a later draft that adopts a name we already took
     * from colliding with us -- `options` is not implausible for a future draft.
     *
     * Chosen over OpenAPI's `x-`, which is normative only for OpenAPI's *own* objects (`info`, `operation`)
     * and has no standing in JSON Schema, where unknown keywords are ignored and `$vocabulary` is the formal
     * extension mechanism. RFC 6648 deprecated `x-` for application protocols, and the `x-` families that are
     * genuinely load-bearing (`x-amazon-*`, `x-ms-*`, `x-google-*`) are all vendor-segmented anyway -- so the
     * vendor segment is the part doing the work. Revisit only if we emit OpenAPI **3.0** rather than 3.1,
     * whose Schema Object follows JSON Schema 2020-12 and so already ignores what it does not know.
     *
     * **The export rule this encodes, for whoever writes the exporter.** When schema is emitted for
     * third-party tooling, `g-`-prefixed keys are stripped by default, with a small transformer table for the
     * few carrying a standard equivalent: [options] becomes `enum`; [emptyIsAbsent] becomes `minLength: 1`
     * but *only* on a required property (we read `""` as absent, while `required` is about key presence, and
     * an optional property accepts `""` -- so an unconditional `minLength` would reject what we allow);
     * [allowCoerce] is dropped, since JSON Schema cannot say "a string is also accepted here"; [visibleOnly]
     * is dropped too, because its nearest spelling (`pattern` over `\p{C}` and `\p{Z}` classes) is honored by
     * some regex engines and not others, so it would be stricter for one consumer and meaningless for the
     * next. [outerWhitespace] exports, in *both* modes, as `pattern: "^\S(?:[\s\S]*\S)?$"` -- exactly what
     * `"reject"` accepts, and stricter than `"trim"` (which would accept the whitespace and clean it), so it
     * honors the stricter-than-us rule; its `\s` is the regex Unicode-ish class, a near-match for our `<= ' '`.
     * Stripping by default is exhaustive by construction: a keyword nobody remembered to consider never
     * escapes. And where
     * a conversion cannot be exact, the export must be **stricter than us, never looser** -- a stricter export
     * means clients send a subset of what we accept, while a looser one manufactures rejections at the
     * boundary, with the client's own tooling calling a payload valid and us returning a 400.
     *
     * Nothing consumes an export today, so none of that is built (issue #194).
     */
    const val gPrefix = "g-"

    /** Whether a value may be coerced to the property's type during validation. */
    const val allowCoerce = "g-allowCoerce"

    /** Whether an empty (or null) value for a property means the property was not supplied at all. */
    const val emptyIsAbsent = "g-emptyIsAbsent"

    /**
     * Whether a string may hold only characters with a clearly visible rendering (issue #543): the ordinary
     * space, or anything outside Unicode's `C` (control, format, surrogate, private-use, unassigned) and `Z`
     * (space, line and paragraph separator) categories. Off unless declared; only a plain string type may
     * declare it.
     *
     * The worry it answers is a value that renders as one thing and *is* another: a bidi override that makes
     * text read backwards, a zero-width space splitting a word, a no-break space that looks like a space and
     * compares unequal, a tab in a name. The ordinary space is the single exception because it is the only
     * whitespace a user types on purpose; the rest arrive by paste or autocorrect. Not addressed, on purpose:
     * a Cyrillic letter standing in for a Latin one. Both are visible, and telling them apart is script-mixing
     * detection (UTS #39), a different and larger job.
     *
     * A known cost, accepted for now: Persian and several Indic scripts spell some words with the zero-width
     * non-joiner (U+200C) or joiner (U+200D), and both are format characters this refuses. Carve out U+200C
     * alone if a user in those scripts ever hits it -- the joiner is also the classic glue of a homoglyph
     * attack, so it stays out.
     */
    const val visibleOnly = "g-visibleOnly"

    /**
     * Whether leading/trailing whitespace on a string value is stripped or refused (issue #541). A closed
     * string vocabulary ([SOWS]) on a plain string property: absent leaves whitespace alone (the default,
     * as today), `"trim"` strips it, `"reject"` fails a value that carries any. Off unless declared, and only
     * a plain string type may declare it -- the parser refuses it elsewhere.
     *
     * One keyword for both modes because "trim it" and "reject it" are the same rule read two ways; two
     * booleans would need a precedence rule for the both-set case. `"reject"` is for a field where silent
     * trimming would hide a paste error (a code, a password, an identifier); `"trim"` is for ordinary free
     * text. "Whitespace" here is the kernel's `<= ' '` test (see the validator's whitespace helper), not
     * Kotlin's Unicode [trim]; the two disagree on a no-break space, and the rest of the kernel uses `<= ' '`.
     */
    const val outerWhitespace = "g-outerWhitespace"

    /**
     * Marks a property the client does not supply -- something else produces it (issue #254).
     *
     * Two forms, both accepted from the start so that widening later is not a migration of stored documents:
     * `true` says only *that* the value is produced elsewhere, and an object will one day say *how*. Only the
     * first is interpreted today; a code-backed pre-processor is the everyday case and will keep using it
     * indefinitely, since the object form is for derivations somebody authors.
     */
    const val derived = "g-derived"

    /** A labeled choice list on a property (array of `{label, value}` entries). */
    const val options = "g-options"

    /**
     * Whether this property's [options] are **suggestions rather than a bound** -- the value may be one of
     * them or anything else the field's other constraints admit (issue #418).
     *
     * A fact about the **list**, not about where it came from, which is why it is its own keyword rather than
     * something [optionsSource] implies. A written-down list can be open, and a sourced one could in
     * principle be closed; they arrive together in practice because a list assembled per caller reflects what
     * *this* caller has seen rather than the universe of legal values.
     *
     * Two readers act on it, and they are the whole of its meaning. The validator stops reporting
     * `invalidOption`, so an off-list value is accepted. A data-entry surface draws a combobox rather than a
     * closed dropdown, so an off-list value can be entered at all -- a control that refuses what the server
     * accepts is the same advertise-versus-serve drift as one that offers what the server refuses.
     *
     * **Under a client alteration it is asymmetric**, unlike [optionsSource]. Turning it *off* -- closing an
     * open list -- narrows the type: an open list accepts anything, so bounding it accepts a subset, which is
     * narrowing rule 2 wearing this keyword, and a client may do it. Turning it *on* -- opening a list the base
     * closes -- widens, and `SchNarrowing` refuses that direction. (Adding an open list to a field the base
     * left optionless is not opening anything: it accepts what the base did, so it is allowed too.)
     */
    const val openOptions = "g-openOptions"

    /**
     * The ordered field names that identify one element of an array of this type (issue #487) -- a primary key.
     * A type-level keyword like [required] and beside it, because a composite key is ordered and it is how SQL
     * states the same thing. Absent means single-instance. Surfaced on `SchType.primaryKey`; what enforces
     * uniqueness across the stored elements is the layer that holds them (a gedra's `checkEntryKeys`), never a
     * single type against one value.
     */
    const val primaryKey = "g-primaryKey"

    /**
     * Marks an object-valued **property** whose value is a *fragment* (issue #487): validate it against its
     * type -- field types, options, nested shape -- but do **not** enforce its **completeness**, meaning
     * neither that type's [required] nor its conditional (`if`/`then`/`else`) requiredness. A `default` is
     * still injected; only the demand that missing fields be present is waived. It relaxes only the immediate
     * object, not objects nested inside it.
     *
     * On the property rather than the type, because a `$ref` field's target type is shared: a gedra edit and a
     * gedra entry both point at the same trait data type, and only the edit's copy is a fragment. It is what
     * lets a keyed trait be deleted by sending its key alone, and a page merge send the fields it owns and no
     * others, without the type having to drop a `required` that a complete record genuinely has. Completeness
     * is then checked where the fragment is assembled into a whole (a gedra's `checkStoredEntries`), not on the
     * way in. Surfaced on `SchProperty.optionalContents`; there is no standard JSON Schema equivalent, so an
     * export drops it (a stricter reader that keeps `required` refuses a subset of what we accept -- see
     * [gPrefix]).
     */
    const val optionalContents = "g-optionalContents"

    /**
     * Names a registered callback that produces this property's [options] when the schema is rendered, in
     * place of a list written into the document (issue #413).
     *
     * **It never reaches a reader.** The rendering pass consumes it: the resolved choices are written into
     * [options] on a copy of the node and this key is dropped, so a frontend sees an ordinary choice list and
     * neither knows nor cares that it was assembled per caller. That is what keeps every schema consumer --
     * the form engine, the outline, a future export -- free of a second way to have options.
     *
     * **It takes no part in validation**, which is the property that makes a per-caller list safe at all. The
     * callback's answer is never parsed into a [SchType], so there is no code path by which one caller's list
     * can reject another caller's value; a field that must actually be bounded is enforced by its handler,
     * which can say why. The design notes' own test for this puts it on the presentation side of the line:
     * two use-sites may legitimately disagree about the list, and the server does not care which they see.
     *
     * Mutually exclusive with a declared [options] list -- both together would need a merge rule and an
     * answer to whether the declared half is binding, and neither has a use yet. Refused at boot, along with
     * an id no callback was registered under.
     */
    const val optionsSource = "g-optionsSource"

    /**
     * A **cfact expression** (`CFactRegistry` syntax) deciding whether this property is shown to a given caller
     * (issue #545). Resolved when the endpoint catalog renders for that caller, exactly like [optionsSource]:
     * the property is kept when the caller's assembled cfacts satisfy the expression and **dropped entirely**
     * -- from its `properties` map and from the enclosing `required` -- when they do not, and the keyword is
     * stripped from what survives. So an ordinary user is not shown an administrator's `user` selector, and a
     * non-env-authed caller is not shown a privileged toggle.
     *
     * **Presentation, never a gate.** Dropping a field from the *rendered* schema hides it; it does not defend
     * it. Request validation runs against the compiled schema, which still carries the field, so a handler that
     * accepts a `g-visibleWhen` field must enforce the same condition itself -- the keyword is the advertise
     * half of an advertise-and-enforce pair, the same relationship [optionsSource] has with a handler that
     * bounds its own input. It takes no part in validation and has no export row: a hidden field is not a
     * stricter document, and a consumer that never sees the field cannot send it.
     */
    const val visibleWhen = "g-visibleWhen"

    /**
     * Per-field error copy: a map from an error key to the message to show when that failure is reported against
     * this field. The keys are [SchFailCode] names plus [errorDefault]; anything else fails at boot.
     *
     * The first keyword here that cannot change what is *accepted* -- it only changes what a rejection says.
     * That makes it the simple case for the export rule on [gPrefix]: stripped, with no transformer-table row
     * needed, because dropping something that has no bearing on validity can make an exported schema neither
     * stricter nor looser.
     */
    const val errors = "g-errors"

    /** The [errors] key used when no message is declared for the specific failure code. Not a failure code,
     *  and cannot collide with one -- no [SchFailCode] entry is named "default". */
    const val errorDefault = "default"

    /**
     * Display label of an [options] entry. Bare rather than `g-`-prefixed: it is a field *inside* the value of
     * one of our keywords, not a keyword in the schema keyword namespace, so it has nothing to collide with.
     */
    const val label = "label"

    /** Stored value of an [options] entry; bare for the same reason as [label]. */
    const val value = "value"

    /**
     * A **presentation hint** for a read-only surface: how a value of this type/field should be *displayed*,
     * with no effect on validation (issue #540). Carries a value from [PRES] -- `status` (colour by verdict),
     * `table` (render an array of the type as rows/columns), `identifier` (monospace). Purely advisory: a
     * renderer that does not recognize the hint falls back to its ordinary rendering, and validation ignores it
     * entirely, so an endpoint declaring one still validates exactly as before.
     *
     * The point is that an endpoint declares *how it wants to be read* beside its schema, so a diagnostic page
     * is not a hand-coded per-endpoint view that drifts when a field is renamed -- the display follows the
     * schema the same way the catalog's outline already does. Surfaced on `SchType.presentation`.
     */
    const val presentation = "g-presentation"
}

/** Values of the JSON Schema `type` keyword (object/null collide with Kotlin
 *  hard keywords -> k prefix). */
@Suppress("ConstPropertyName", "unused")
object SCT {
    const val string = "string"
    const val number = "number"
    const val integer = "integer"
    const val boolean = "boolean"
    const val array = "array"
    const val kObject = "object"
    const val kNull = "null"
}

/** Values of the JSON Schema `format` keyword that we act on. The `dateTime` name does not match its
 *  hyphenated value, which is the standard JSON Schema spelling. */
@Suppress("ConstPropertyName", "unused")
object SFMT {
    /** A day-only date, e.g. `2021-06-01`. */
    const val date = "date"

    /** A full timestamp, e.g. `2021-06-01T08:00:00.000Z`. */
    const val dateTime = "date-time"

    /**
     * File content: `{"type": "string", "format": "binary"}`. This is OpenAPI's own way of saying "this field
     * is a file" — the same spelling an OpenAPI 3.0 document uses for an upload part or a downloaded body — so
     * a schema declaring one reads as a standard document rather than a house invention.
     *
     * It is a *string* type carrying a format for the same reason OpenAPI does it: JSON Schema has no binary
     * type, and a file is only ever a string in the sense that the wire is bytes. Nothing decodes it as text —
     * the value at runtime is a `ContentData`, and [isBinaryFormat] is what tells the validator to leave it
     * alone rather than coerce it (see the validator's note).
     *
     * OpenAPI 3.1 instead layers `contentMediaType`/`contentEncoding` onto JSON Schema proper. We use the 3.0
     * spelling because it is the one still universally understood by tooling, and because a `format` is
     * something this schema layer already models.
     */
    const val binary = "binary"
}

/**
 * Values of the [SCH.outerWhitespace] keyword (issue #541): the two modes for edge whitespace on a string.
 * A closed set -- an unrecognized value fails the parse -- resolved onto [SchType.outerWhitespace].
 */
@Suppress("ConstPropertyName", "unused")
object SOWS {
    /** Strip leading/trailing whitespace (coerce mode); validate-only checks the trimmed form. */
    const val trim = "trim"

    /** Fail a value carrying leading/trailing whitespace with `badValue`; alter nothing. */
    const val reject = "reject"
}

/**
 * Values of the [SCH.presentation] hint (issue #540): how a read-only surface should display a value. Advisory
 * only -- a renderer that does not know a value renders ordinarily, and validation never consults these.
 */
@Suppress("ConstPropertyName", "unused")
object PRES {
    /** A verdict field, coloured by its [PSTAT] value (green/blue/amber/red) rather than shown as plain text. */
    const val status = "status"

    /** A type whose array is rendered as a table -- one row per element, its properties the columns. */
    const val table = "table"

    /** A value shown monospaced: an id, a hash, a path, an env-var name -- something scanned character by character. */
    const val identifier = "identifier"

    /** On a **property** whose value is an array of objects, inside a [table]-rendered row: render that array as
     *  its own labelled sub-table on a full-width row *beneath* the main row, rather than as an inline cell.
     *  Master-detail -- for a heavy nested array (a database table's `columns`) that would otherwise force the
     *  row very wide. Ignored on a property that is not a structured array. */
    const val detail = "detail"
}

/**
 * Values a [PRES.status] field may carry, in increasing severity, each with a conventional colour a renderer
 * maps (issue #540). A shared vocabulary so the server states the verdict and the frontend colours it the same
 * way, rather than each side inventing its own strings.
 */
@Suppress("ConstPropertyName", "unused")
object PSTAT {
    /** Nothing wrong: green. */
    const val ok = "ok"

    /** Worth noting, not a problem: blue/neutral. */
    const val info = "info"

    /** Attention: amber. */
    const val warning = "warning"

    /** A failure: red. */
    const val error = "error"

    /** Every status, ordered by increasing severity -- the one place the vocabulary and its order live, so the
     *  schema (an `options` list), the chip colours, and any severity test read the same set rather than a copy. */
    val all: List<String> = listOf(ok, info, warning, error)

    /** Whether a status calls for attention: at or above [warning] in [all]. Anything the list does not know
     *  is treated as not-attention (it is shown, but not counted against the "all clear" verdict). */
    fun needsAttention(status: String): Boolean {
        val i = all.indexOf(status)
        return i >= all.indexOf(warning)
    }
}
