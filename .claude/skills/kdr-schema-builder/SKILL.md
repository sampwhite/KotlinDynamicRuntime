---
name: kdr-schema-builder
description: Author, review, or apply JSON Schema in KotlinDynamicRuntime using the Sch* layer — the builder DSL (schemaDefs/type/property, SCH/SCT/SFMT constants, required-on-the-side, $ref, reusable clone-and-mutate properties), plus parsing (parseSchemaTypes) and validate/coerceAndValidate with the allowCoerce coercion rules. Use when writing schema definitions or validating/coercing data against them.
---

# Authoring schema definitions (Sch* builders)

The schema layer lives in module `base/common`, package
`com.dynamicruntime.common.schema`. It builds JSON Schema (draft 2020-12) as
insertion-ordered `Map<String, Any?>` values via a Kotlin DSL.

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
  collision → `k` prefix (`SCH.kIf`/`kThen`/`kElse`).
- `SCT` — `type` values (`SCT.string`, `SCT.integer`, `SCT.kObject`, `SCT.kNull`, …).

## Dates in a schema

A string field can declare a date `format` (values in `object SFMT`): `dayOnlyDate()`
(`format = SFMT.date`, `yyyy-MM-dd`) or `dateTime()` (`format = SFMT.dateTime`). A date
format makes the field validate by parsing and defaults `allowCoerce` to true.

## Validation & coercion

Parse the built `$defs` map into resolved types, then validate/coerce data:

```kotlin
val types = parseSchemaTypes(defs, existingTypes = emptyMap()) // resolves $refs; unknown -> KdrException
val type  = types["core.Person"]!!
val failures: List<SchFailure> = validate(type, data)          // collects ALL failures, no transform
val result: SchResult          = coerceAndValidate(type, data) // .value (coerced) + .failures; input never mutated
```

`allowCoerce` (custom keyword; default **true** for numeric + date-format types, **false**
otherwise) governs coercion of a mismatched value — and changes validation even when no
output is requested:

- number/integer strings → `Long`/`Double`; string ← any non-null (`toString`).
- boolean ← string via `toOptBool` (blank → null, unrecognized → failure).
- date-format string → `Instant`; array/object ← JSON string (`[`→`jsonArray`, else comma-split;
  `jsonMap`), then re-validated element/property-wise.
- Missing required properties with a `default` are injected (deep-cloned), not failed.

`SchFailCode`: `missingRequired`, `invalidOption`, **`wrongType`** (a plain type check
rejected it), **`badValue`** (its content was inspected and failed to coerce). A
parse-driven `badValue` carries the parser exception in `SchFailure.cause`.

## Casts

Don't write `as`/`@Suppress("UNCHECKED_CAST")`. Use `com.dynamicruntime.common.util`:
`toT()` (coerce to a type param), `toJsonMap()` (coerce to `Map<String,Any?>`).

## Source files

- `schema/SchemaConstants.kt` (SCH/SCT/SFMT), `schema/SchTypeBuilder.kt`,
  `schema/SchTypesBuilder.kt`, `schema/SchParser.kt`, `schema/SchValidator.kt`
- `util/CollectionUtil.kt` (`deepClone`), `util/ConvertUtil.kt` (`toT`/`toJsonMap`/`toOptStr`)
- Tests: `schema/SchTypeBuilderTest.kt`, `schema/SchValidatorTest.kt`

For building HTTP endpoints on top of this layer, see the `kdr-endpoint-builder` skill.
