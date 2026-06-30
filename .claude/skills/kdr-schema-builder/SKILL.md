---
name: kdr-schema-builder
description: Author or review JSON Schema type/property definitions in KotlinDynamicRuntime using the Sch* builder DSL — schemaDefs/type/property, the SCH/SCT keyword constants (d/k naming), required-on-the-side, $ref cross-references, reusable clone-and-mutate properties. Use when writing or reviewing schema definitions in this codebase.
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

## Casts

Don't write `as`/`@Suppress("UNCHECKED_CAST")`. Use `com.dynamicruntime.common.util`:
`toT()` (coerce to a type param), `toJsonMap()` (coerce to `Map<String,Any?>`).

## Source files

- `schema/SchemaConstants.kt`, `schema/SchTypeBuilder.kt`, `schema/SchTypesBuilder.kt`,
  `schema/SchProperty.kt`
- `util/CollectionUtil.kt` (`deepClone`), `util/ConvertUtil.kt` (`toT`/`toJsonMap`/`toOptStr`)
- Tests: `schema/SchTypeBuilderTest.kt`

Planned (not yet built): custom `isEndpoint` / `isTable` keywords and their builders.
