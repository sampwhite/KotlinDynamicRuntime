package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The pure search logic behind issue #538: what parameters a set of usage rules contributes, the schema
 * properties they become, and how the predicate compares a value. Kept as a focused test because the
 * comparison is where a bug would fail *quietly* -- a range that silently drops every row, an exact match that
 * is really a substring -- rather than throwing.
 */
class GedraSearchTest : StringSpec({
    fun usage(traitId: String, kind: UsageKind, substring: Boolean = false) =
        ClientTraitUsage(traitId, traitId.replaceFirstChar { it.uppercase() }, $$"${x}", kind, substring)

    "a string usage gives an exact parameter, and a substring one only when asked" {
        gedraSearchParams(listOf(usage("name", UsageKind.string))).map { it.name to it.role } shouldBe
            listOf("name" to SearchRole.exact)
        gedraSearchParams(listOf(usage("name", UsageKind.string, substring = true))).map { it.name to it.role } shouldBe
            listOf("name" to SearchRole.exact, "nameContains" to SearchRole.contains)
    }

    "a number or date usage gives a >= / <= pair named for the trait" {
        gedraSearchParams(listOf(usage("year", UsageKind.number))).map { it.name to it.role } shouldBe
            listOf("yearMin" to SearchRole.min, "yearMax" to SearchRole.max)
        gedraSearchParams(listOf(usage("due", UsageKind.date))).map { it.name to it.role } shouldBe
            listOf("dueMin" to SearchRole.min, "dueMax" to SearchRole.max)
    }

    "the generated properties carry the type each kind searches as" {
        val props = searchParamProperties(
            gedraSearchParams(
                listOf(
                    usage("name", UsageKind.string, substring = true),
                    usage("year", UsageKind.number),
                    usage("due", UsageKind.date),
                ),
            ),
        )
        // A string parameter is a plain string; the exact and contains variants are both untyped text.
        (props["name"] as Map<*, *>).containsKey(SCH.type) shouldBe false
        // A number bound is a number and coerces from the query string.
        (props["yearMin"] as Map<*, *>)[SCH.type] shouldBe SCT.number
        (props["yearMin"] as Map<*, *>)[SCH.allowCoerce] shouldBe true
        // A date bound is a string in date format (which coerces on its own).
        (props["dueMin"] as Map<*, *>)[SCH.type] shouldBe SCT.string
        (props["dueMin"] as Map<*, *>)[SCH.format] shouldBe SFMT.date
    }

    "exact matches case-insensitively; contains is a substring" {
        val params = gedraSearchParams(listOf(usage("name", UsageKind.string, substring = true)))
        val exact = params.first { it.role == SearchRole.exact }
        val contains = params.first { it.role == SearchRole.contains }
        matchesSearch(mapOf("name" to "Quarterly Plan"), listOf(exact to "quarterly plan")) shouldBe true
        matchesSearch(mapOf("name" to "Quarterly Plan"), listOf(exact to "quarterly")) shouldBe false
        matchesSearch(mapOf("name" to "Quarterly Plan"), listOf(contains to "arterly")) shouldBe true
        matchesSearch(mapOf("name" to "Quarterly Plan"), listOf(contains to "annual")) shouldBe false
    }

    "a number range keeps values within the bounds and drops a non-numeric value" {
        val params = gedraSearchParams(listOf(usage("year", UsageKind.number)))
        val lower = params.first { it.role == SearchRole.min }
        val upper = params.first { it.role == SearchRole.max }
        matchesSearch(mapOf("year" to "2026"), listOf(lower to "2025")) shouldBe true
        matchesSearch(mapOf("year" to "2024"), listOf(lower to "2025")) shouldBe false
        matchesSearch(mapOf("year" to "2024"), listOf(upper to "2025")) shouldBe true
        matchesSearch(mapOf("year" to "2026"), listOf(lower to "2025", upper to "2027")) shouldBe true
        // An empty (or non-numeric) display value is not on the number line, so it is out of any range.
        matchesSearch(mapOf("year" to ""), listOf(lower to "2025")) shouldBe false
    }

    "a date range compares chronologically" {
        val params = gedraSearchParams(listOf(usage("due", UsageKind.date)))
        val lower = params.first { it.role == SearchRole.min }
        val upper = params.first { it.role == SearchRole.max }
        matchesSearch(mapOf("due" to "2026-06-01"), listOf(lower to "2026-01-01")) shouldBe true
        matchesSearch(mapOf("due" to "2025-12-31"), listOf(lower to "2026-01-01")) shouldBe false
        matchesSearch(mapOf("due" to "2026-06-01"), listOf(upper to "2026-12-31")) shouldBe true
    }

    "a row with no value for a searched trait fails that trait's parameter" {
        val params = gedraSearchParams(listOf(usage("name", UsageKind.string)))
        val exact = params.first()
        // The display map has no `name` entry at all -- the row carries no such trait.
        matchesSearch(emptyMap(), listOf(exact to "anything")) shouldBe false
    }

    "a search parameter colliding with a reserved query field is detected and never overwrites it" {
        // A `string` usage on a trait named `user` mints an exact parameter `user`, which the reserved-field set
        // catches (`offset`/`limit`/`user`); a `name` usage collides with nothing.
        searchParamCollisions(listOf(usage("user", UsageKind.string))) shouldBe listOf("user")
        searchParamCollisions(listOf(usage("name", UsageKind.string))) shouldBe emptyList()
        // The base's own `user` field (with its admin-only gate) survives the merge -- the colliding search
        // property is dropped rather than allowed to rewrite the listing's user filter.
        val base = mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf("user" to mapOf(SCH.visibleWhen to "hasAdminLevel")),
        )
        @Suppress("UNCHECKED_CAST")
        val userProp = (withSearchProperties(base, listOf(usage("user", UsageKind.string)))[SCH.properties]
            as Map<String, Any?>)["user"] as Map<*, *>
        userProp[SCH.visibleWhen] shouldBe "hasAdminLevel"
    }

    "withSearchProperties merges into the base and leaves a usage-less base untouched" {
        val base = mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf("offset" to mapOf(SCH.type to SCT.integer)),
        )
        val augmented = withSearchProperties(base, listOf(usage("name", UsageKind.string)))
        @Suppress("UNCHECKED_CAST")
        val props = augmented[SCH.properties] as Map<String, Any?>
        props.containsKey("offset") shouldBe true // the stable field survives
        props.containsKey("name") shouldBe true // the search field is added
        // No usages contribute nothing, and the exact same map comes back (an inheriting scope shares it).
        withSearchProperties(base, emptyList()) shouldBe base
    }
    // The free-text term (issue #562): one box searched across every text field.
    "textSearchTraitIds is the string usages only, in order" {
        textSearchTraitIds(
            listOf(usage("name", UsageKind.string), usage("year", UsageKind.number), usage("auditor", UsageKind.string, substring = true)),
        ) shouldBe listOf("name", "auditor")
    }

    "matchesAnyText ORs a case-insensitive substring across the text fields, and a blank term matches all" {
        val byTrait = mapOf("name" to "Quarterly plan", "auditor" to "Ada Lovelace", "year" to "2026")
        val text = listOf("name", "auditor")
        // Found via either field -- any one match is enough.
        matchesAnyText(byTrait, text, "quarter") shouldBe true
        matchesAnyText(byTrait, text, "LOVELACE") shouldBe true
        // Present only in a non-text field: not searched, so not a match.
        matchesAnyText(byTrait, text, "2026") shouldBe false
        // Nothing carries it.
        matchesAnyText(byTrait, text, "zzz") shouldBe false
        // A blank or whitespace term is no search, not a search for nothing.
        matchesAnyText(byTrait, text, "") shouldBe true
        matchesAnyText(byTrait, text, "   ") shouldBe true
        // A row with no text values matches only a blank term.
        matchesAnyText(emptyMap(), text, "quarter") shouldBe false
        matchesAnyText(emptyMap(), text, "") shouldBe true
    }

    // Issue #562: a UI reads the parameters back off the listing's schema to group each trait's controls, so the
    // naming must round-trip -- every generated parameter decodes to the trait, role and kind that made it.
    "a generated parameter decodes back to its trait, role and kind from its name and schema" {
        val usages = listOf(
            usage("name", UsageKind.string, substring = true),
            usage("year", UsageKind.number),
            usage("due", UsageKind.date),
            // Trait ids that end like a suffix: the schema, not the spelling, says whether one is a bound.
            usage("vitaMin", UsageKind.string),
            usage("climax", UsageKind.number),
        )
        val params = gedraSearchParams(usages)
        val props = searchParamProperties(params)
        for (param in params) {
            val shape = decodeSearchParam(param.name, props[param.name].toJsonMapOrEmpty())
            Triple(shape.traitId, shape.role, shape.kind) shouldBe Triple(param.traitId, param.role, param.kind)
        }
        // A trait a client could not have generated -- typed, but with neither ending -- still reads as a control.
        decodeSearchParam("year", mapOf(SCH.type to SCT.number)).role shouldBe SearchRole.exact
    }
})
