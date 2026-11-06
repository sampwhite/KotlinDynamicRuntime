<!-- kdr-generated: begin -- copied from its generator by `./gradlew :launch:refDocsDump`; edit the generator, not this -->
# The schema dialect

The runtime describes data with JSON Schema, and reads a dialect of it: most of the standard keywords, some refused,
and keywords of its own that begin with `g-`. This page lists them all. It is made from the tables the
runtime's schema parser reads, so it says exactly what the parser accepts. The same content is served as JSON, as a
schema for schemas, at `GET /schema/metaSchema`.

A schema here is an open record. The keywords below are read and held to the shape each lists. Any other key is the
document's own and passes unread, except a key beginning `g-` that is not one of ours, which is refused as
a misspelling.

## Standard keywords the runtime reads

These keep their JSON Schema meaning, within the limits each one states.

| Keyword | Value | Meaning |
|---|---|---|
| `type` | One of string, number, integer, boolean, array, object, null | The type of the value. One type only: a list of types is not supported. |
| `format` | Text | A named format a string must take, such as 'date-time' or 'email'. |
| `title` | Text | A short name for the type or property, for a reader. |
| `description` | Text | What the type or property is, for a reader. |
| `$ref` | Text | A reference to a named type, which this schema then is. |
| `required` | A list of property names | The properties an object must have. |
| `properties` | An object of schema objects | An object's properties, each a schema by name. |
| `items` | A schema object | The schema every item of an array takes. |
| `additionalProperties` | True, false or a schema object | On a record, whether properties it does not declare are allowed; as a schema, the object is a map whose every value takes that schema. |
| `oneOf` | A list of schema objects | Branches of which the value takes exactly one, told apart by a 'discriminator', which it must have. |
| `minimum` | A number | The smallest number allowed, inclusive. |
| `maximum` | A number | The largest number allowed, inclusive. |
| `minLength` | A number | The fewest characters a string may have. |
| `maxLength` | A number | The most characters a string may have. |
| `minItems` | A number | The fewest items an array may have. |
| `maxItems` | A number | The most items an array may have. |
| `minProperties` | A number | The fewest properties an object may have. |
| `maxProperties` | A number | The most properties an object may have. |

## Standard keywords read more closely

The runtime reads these too, and holds each to more than the shape of its value: what one accepts turns on the type it
sits on, or on the keywords beside it. The parser checks each as it reads the schema, and says what is wrong in its
own words.

| Keyword | Value | Meaning |
|---|---|---|
| `pattern` | Text, a regular expression | A regular expression a plain string must match. The match is unanchored, as JSON Schema has it, so '[0-9]' accepts any value holding a digit; write '^...$' to match the whole value. Only the part of ECMA-262's syntax that the server and the browser read alike is accepted: a construct they read differently, such as an inline flag, is refused, and there are no flags. Refused on any type but a plain string, and on a date or binary format. |
| `const` | Any value | The one value allowed. Most often on a union branch's discriminator property, saying which branch it is. Refused on a date or binary format, which is never compared against it. |
| `default` | Any value | The value a required property takes when it is missing, so a missing one passes. A null is no default. |
| `uniqueItems` | True or false | On an array, whether no two items may be equal, compared as JSON values: 1 equals 1.0, and two objects are equal whatever the order of their keys. |
| `exclusiveMinimum` | A number | On a number, the bound a value must be above, not equal to. The bound itself, as JSON Schema 2020-12 has it: draft 4's true or false beside 'minimum' is refused. Beside 'minimum', the stricter of the two applies. Read on a number or an integer only. |
| `exclusiveMaximum` | A number | On a number, the bound a value must be below, not equal to. The bound itself, as JSON Schema 2020-12 has it: draft 4's true or false beside 'maximum' is refused. Beside 'maximum', the stricter of the two applies. Read on a number or an integer only. |
| `if` | A schema object testing one property with a 'const' | Makes 'then' or 'else' apply by one property's value: '{"properties": {"kind": {"const": "talk"}}}'. Only that shape is read, one property compared with a 'const'; anything more general is refused rather than half-applied. |
| `then` | An object holding 'required', 'not', or both | What applies when the 'if' holds: 'required' names properties that must be present, and 'not': {'required': [...]} names properties that must be absent. Nothing else may be in it. 'then' and 'else' are the only places 'not' is read. |
| `else` | An object holding 'required', 'not', or both | What applies when the 'if' does not hold, written as 'then' is. |
| `discriminator` | An object holding 'propertyName', and 'defaultMapping' if wanted | Beside a 'oneOf', which must have one: 'propertyName' names the property whose value says which branch a value takes, and every branch declares that property with a 'const' of its own. 'defaultMapping', a '$ref' to a branch, takes a value naming no branch; without it, such a value is a failure. |

## The runtime's own keywords

A key beginning `g-` belongs to the runtime, and the list of them is closed. A stock JSON Schema validator
ignores them, as it ignores any keyword it does not know. They are listed here by where each one is written. The codes
a refused value is reported with, and how `g-errors` gives one a message of its own, are in
[Validation messages](validation-messages.md); what a `g-layout` block may hold is in
[Form layouts](form-layouts.md).

### On a schema

Wherever a schema is written: a type declared by name, a field's own schema, or an array's item schema.

| Keyword | Value | Meaning |
|---|---|---|
| `g-allowCoerce` | True or false | Whether a value may be converted to the property's type when it is validated, such as the text '5' to a number. |
| `g-emptyIsAbsent` | True or false | Whether an empty or null value means the property was not supplied at all. |
| `g-visibleOnly` | True or false | Whether a string may hold only characters with a visible rendering, and the ordinary space. |
| `g-outerWhitespace` | Text | What to do with leading and trailing whitespace on a string: 'trim' it, 'reject' a value that has any, or 'keep' it. Unset, a plain string is trimmed when it arrives in a request, and left as it is when stored or sent back. |
| `g-longText` | True or false | Marks a string as long text, free text that may run over several lines, which a form draws as a text area. |
| `g-derived` | True, false or an object | Marks a property the caller does not supply, because something else produces it. |
| `g-schemaDocument` | True or false | Marks a property whose value is itself a schema, checked by parsing it. |
| `g-options` | A list | The choices a property offers, each a label and a value. |
| `g-openOptions` | True or false | Whether the options are suggestions rather than a bound, so another value is allowed. |
| `g-primaryKey` | A list of text | The fields, in order, that tell one value of this type from another: a trait's entries, or the items of an array. Each must be declared, required, and a scalar. |
| `g-errors` | An object | Messages to show for a failure of this field, keyed by the failure. |
| `g-presentation` | One of [detail, identifier, status, table] | How a read-only page should display the value: as a status, a table, an identifier, or a nested table beneath its row (detail). On a type, or on a field beside its reference. It does not affect validation. |

### On a field

On a field in `properties`, about that field's use of its type. These may stand beside a `$ref`, where other keywords may not.

| Keyword | Value | Meaning |
|---|---|---|
| `g-optionalContents` | True or false | Marks an object property whose value may be a fragment: each field is checked, but not that the required ones are present. |
| `g-optionsSource` | Text | Names a registered source that supplies the property's options when the schema is served. |
| `g-visibleWhen` | Text | A cfact expression deciding whether the property is shown to a caller, and whether they may change it. |
| `g-sensitive` | True or false | Marks a property whose value is a secret, such as a password or a code, so it is never logged. |

### At the top of a named type

On a type declared by name in `$defs`, and refused anywhere else.

| Keyword | Value | Meaning |
|---|---|---|
| `g-onWrite` | A list of function objects | The functions run on the object's data each time it is written, such as one computing a derived property from the others. Each names its kind in 'fn'. Always a list, even of one. |
| `g-layout` | An object | How a form lays out the type's fields. Served apart from the schema, never read as part of it. |
| `g-merge` | An object | On a client's alteration of a global type, how a part of it merges with the type it alters. |
| `g-extends` | Text | On a named type a configuration declares, the type it extends: that type plus this one's body. |
| `g-appliesTo` | A list of text | The kinds of form a trait's entry type applies to. |

## Standard keywords that are refused

These are valid JSON Schema, and a schema that uses one is refused, with what to write instead.

| Keyword | Instead |
|---|---|
| `enum` | Declare the choices with 'g-options' (the builder's option(...)); they export as 'enum'. |
| `allOf` | Compose by extending a type, or declare the properties on the type itself. |
| `anyOf` | Use a discriminated 'oneOf', whose branches say which one they are. |
| `not` | Constrain what is accepted directly; a negated schema is not supported. Inside a 'then' or 'else' clause, 'not': {'required': [...]} forbids properties. |
| `dependentSchemas` | Use 'if'/'then'/'else' to require or forbid properties by another's value. |

## Rules the shapes do not state

The parser applies these, and the JSON form cannot express them, so it lists them beside itself. The closed list of
`g-` keywords and the refused keywords are covered above.

- **`additionalProperties` takes more than one kind of value:** true, false or a schema object. The JSON form has no way to say "this or that", so it leaves the value untyped and the parser checks it.
- **`g-derived` takes more than one kind of value:** true, false or an object. The JSON form has no way to say "this or that", so it leaves the value untyped and the parser checks it.
- **An entry of `properties` may be null**, meaning the name is not set. That is how a client's change to a global type removes one. The JSON form says every entry is a schema, so it would refuse the null.
- **`g-extends` and `g-merge` are read only at the top of a type a configuration declares**, and refused anywhere else, whatever their value.

## What a shape does not decide

A schema whose every keyword has the right shape is not thereby valid. The parser, and for a client's configuration the
trial load, check these as well:

- Whether a '$ref' names a type that exists, and whose namespace a type may be declared in.
- Whether a keyword applies to the type it sits on, and what it means there: a 'pattern' on an integer, a 'oneOf' with no 'discriminator', a bound below its partner.
- What the keywords read more closely than a shape accept: 'pattern', 'const', 'default', 'uniqueItems', 'exclusiveMinimum', 'exclusiveMaximum', 'if', 'then', 'else', 'discriminator'.
- Whether 'required' names properties the type declares.
- What a client's alteration of a global type may change: it may narrow, never widen.
- The content of 'g-errors', 'g-layout' and 'g-options', each with a vocabulary of its own.
<!-- kdr-generated: end -->

## About this file

**Everything above this section is generated.** A running server makes it from the tables its schema parser reads, so it
cannot disagree with the parser. Read it on the server's **Documents** page, under **Schema dialect**, where anyone may
open it without signing in. On the public reference site it is at
[dynamicruntime.org/wa/docs/schema-dialect](https://dynamicruntime.org/wa/docs/schema-dialect). The same content is
served as JSON, as a schema for schemas, at `GET /schema/metaSchema`. `./gradlew :launch:refDocsDump` copies it
into this file, and the suite fails while the copy is behind the code, so edit the generator (below), never the copy.

On a running server, a link to this file opens the reference itself.

## Where it comes from

| What | Source |
|---|---|
| The standard keywords read, each with its shape and meaning | [`SchStdKeywords.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/schema/SchStdKeywords.kt) |
| The `g-` keywords, each with its shape and meaning | [`SchGKeywords.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/schema/SchGKeywords.kt) |
| The standard keywords refused, each with what to write instead | `refusedKeywords` in [`SchParser.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/schema/SchParser.kt) |
| The schema for schemas, and the rules it cannot state | [`SchMetaSchema.kt`](../../base/kernel/src/commonMain/kotlin/com/dynamicruntime/common/schema/SchMetaSchema.kt) |
| The reference page, made from all of these | [`SchDialectReference.kt`](../../base/common/src/main/kotlin/com/dynamicruntime/common/schema/SchDialectReference.kt) |
