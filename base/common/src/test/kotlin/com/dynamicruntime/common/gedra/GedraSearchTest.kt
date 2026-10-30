package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.SchOption
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.TimeZone

/**
 * The pure search logic behind issue #538: what parameters a set of usage rules contributes, the schema
 * properties they become, and how the predicate compares a value. Kept as a focused test because the
 * comparison is where a bug would fail *quietly* -- a range that silently drops every row, an exact match that
 * is really a substring -- rather than throwing.
 */
class GedraSearchTest : StringSpec({
    fun usage(traitId: String, kind: UsageKind, substring: Boolean = false, columnId: String? = null) =
        ClientTraitUsage(traitId, traitId.replaceFirstChar { it.uppercase() }, $$"${x}", kind, substring, columnId = columnId)

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
        // A day as the upper bound includes the whole day (issue #1500 review); a moment is the moment.
        matchesSearch(mapOf("due" to "2026-12-31T23:30:00Z"), listOf(upper to "2026-12-31"), TimeZone.UTC) shouldBe true
        matchesSearch(mapOf("due" to "2027-01-01T00:00:00Z"), listOf(upper to "2026-12-31"), TimeZone.UTC) shouldBe false
        matchesSearch(mapOf("due" to "2026-12-31T23:30:00Z"), listOf(upper to "2026-12-31T12:00:00Z"), TimeZone.UTC) shouldBe false
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

    "a reserved name is left out of the parameters, so the schema and the predicate drop it together (issue #987)" {
        // A trait named for the cursor field: reported, and minted nowhere -- not even onto a base that does
        // not declare `after` (the framework appends it later, where a second one would refuse the endpoint).
        val after = usage(EP.after, UsageKind.string, substring = true)
        searchParamCollisions(listOf(after)) shouldBe listOf(EP.after)
        // The whole usage goes: an `afterContains` left on its own would read back as another trait's exact match.
        gedraSearchParams(listOf(after)) shouldBe emptyList()
        val base = mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf(EP.offset to mapOf(SCH.type to SCT.integer)))
        withSearchProperties(base, listOf(after, usage("name", UsageKind.string)))[SCH.properties].toJsonMapOrEmpty().keys shouldBe
            setOf(EP.offset, "name")
        // The same for a name the listing declares: the predicate must not read the paging offset as a filter.
        gedraSearchParams(listOf(usage(EP.offset, UsageKind.string))) shouldBe emptyList()
        // Every reserved name is one a usage can ask for and be told about.
        for (name in reservedQueryFieldNames) {
            searchParamCollisions(listOf(usage(name, UsageKind.string))) shouldBe listOf(name)
        }
    }

    "usageColumnClashes names a column declared more than once, once, in first-seen order" {
        // Two columns of one trait with no name of their own -- the case that silently collides (issue #681).
        usageColumnClashes(listOf(usage("year", UsageKind.number), usage("year", UsageKind.string))) shouldBe
            listOf("year")
        // Distinct trait ids: nothing to report.
        usageColumnClashes(listOf(usage("year", UsageKind.number), usage("note", UsageKind.string))) shouldBe
            emptyList()
        // Two distinct duplicates among singles: each named once, in the order first seen.
        usageColumnClashes(
            listOf(
                usage("year", UsageKind.number),
                usage("note", UsageKind.string),
                usage("year", UsageKind.string),
                usage("note", UsageKind.number),
            ),
        ) shouldBe listOf("year", "note")
    }

    "a second column of a trait is told apart by its own name, which names its search parameters" {
        // Issue #1490: the trait twice, the second named, is two columns -- and the first keeps the names it had.
        val two = listOf(usage("amount", UsageKind.number), usage("amount", UsageKind.string, substring = true, columnId = "amountText"))
        usageColumnClashes(two) shouldBe emptyList()
        gedraSearchParams(two).map { it.name to it.columnKey } shouldBe listOf(
            "amountMin" to "amount", "amountMax" to "amount", "amountText" to "amountText", "amountTextContains" to "amountText",
        )
        // A name of its own may be anything but another column's.
        usageColumnClashes(listOf(usage("year", UsageKind.number), usage("note", UsageKind.string, columnId = "year"))) shouldBe
            listOf("year")
        // Nor may the columns' search parameters meet: a text column named `yearMin` would take the number column's bound.
        usageColumnClashes(listOf(usage("year", UsageKind.number), usage("note", UsageKind.string, columnId = "yearMin"))) shouldBe
            listOf("yearMin")
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
    "textSearchColumns is the string usages only, in order, by their columns" {
        textSearchColumns(
            listOf(
                usage("name", UsageKind.string), usage("year", UsageKind.number), usage("auditor", UsageKind.string, substring = true),
                usage("auditor", UsageKind.string, columnId = "auditorAgain"),
            ),
        ) shouldBe listOf("name", "auditor", "auditorAgain")
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
            // Trait ids that end like a suffix: the schema, not the spelling, says whether one is a bound, and a
            // `Contains` ending is a substring parameter only beside the exact one it shortens to.
            usage("vitaMin", UsageKind.string),
            usage("climax", UsageKind.number),
            usage("notesContains", UsageKind.string),
        )
        val params = gedraSearchParams(usages)
        val props = searchParamProperties(params)
        for (param in params) {
            val shape = decodeSearchParam(param.name, props[param.name].toJsonMapOrEmpty(), props.keys)
            Triple(shape.columnKey, shape.role, shape.kind) shouldBe Triple(param.columnKey, param.role, param.kind)
        }
        // A trait a client could not have generated -- typed, but with neither ending -- still reads as a control.
        decodeSearchParam("year", mapOf(SCH.type to SCT.number), setOf("year")).role shouldBe SearchRole.exact
    }

    "compareForSort orders a number column by value, not lexically" {
        // "10" after "2" ascending -- a lexical sort would put "10" first.
        compareForSort("2", "10", UsageKind.number, descending = false) shouldBe -1
        compareForSort("2", "10", UsageKind.number, descending = true) shouldBe 1
    }

    "compareForSort orders a date column chronologically" {
        compareForSort("2024-01-01", "2024-03-01", UsageKind.date, descending = false) shouldBe -1
        compareForSort("2024-01-01", "2024-03-01", UsageKind.date, descending = true) shouldBe 1
    }

    "compareForSort orders a string column case-insensitively" {
        compareForSort("apple", "Banana", UsageKind.string, descending = false) shouldBe -1
        compareForSort("apple", "Apple", UsageKind.string, descending = false) shouldBe 0
    }

    "compareForSort puts a blank or unreadable value last, in either direction" {
        // Blank vs a value: blank is always the greater (sorts after), ascending and descending alike.
        compareForSort("", "5", UsageKind.number, descending = false) shouldBe 1
        compareForSort("", "5", UsageKind.number, descending = true) shouldBe 1
        compareForSort("5", "", UsageKind.number, descending = true) shouldBe -1
        // A non-numeric value in a number column reads as no value -- last, like a blank.
        compareForSort("n/a", "5", UsageKind.number, descending = false) shouldBe 1
        // Two blanks are equal, so the caller's id tiebreak decides.
        compareForSort("", "", UsageKind.string, descending = false) shouldBe 0
    }


    // A column whose field offers choices (issue #1500): Beginners, Some experience, Experts, in that order.
    val levels = listOf(SchOption("beginner", "Beginners"), SchOption("mid", "Some experience"), SchOption("advanced", "Experts"))

    "a column with choices matches a value or its label, exactly or in part" {
        val params = gedraSearchParams(listOf(usage("level", UsageKind.string, substring = true)), mapOf("level" to levels))
        val exact = params.first { it.role == SearchRole.exact }
        val contains = params.first { it.role == SearchRole.contains }
        val row = mapOf("level" to "advanced")
        matchesSearch(row, listOf(exact to "advanced")) shouldBe true
        matchesSearch(row, listOf(exact to "experts")) shouldBe true
        matchesSearch(row, listOf(exact to "exp")) shouldBe false
        matchesSearch(row, listOf(contains to "pert")) shouldBe true
        matchesSearch(row, listOf(contains to "vanc")) shouldBe true
        // A value that is not a choice has no label to match.
        matchesSearch(mapOf("level" to "guru"), listOf(exact to "experts")) shouldBe false
        // The free-text term reads the label too.
        matchesAnyText(row, listOf("level"), "expert", mapOf("level" to levels)) shouldBe true
        matchesAnyText(row, listOf("level"), "expert") shouldBe false
    }

    "compareForSort orders a column with choices in their declared order, others after them" {
        fun sorted(descending: Boolean) = listOf("advanced", "", "guru", "beginner", "mid", "alpha")
            .sortedWith { a, b -> compareForSort(a, b, UsageKind.string, descending, choiceRanks(levels)) }
        sorted(descending = false) shouldBe listOf("beginner", "mid", "advanced", "alpha", "guru", "")
        // Reversed, with the blank still last.
        sorted(descending = true) shouldBe listOf("guru", "alpha", "advanced", "mid", "beginner", "")
    }

    "GSORT.displayColumnKey decodes a namespaced display column, and leaves a fixed or bare one alone" {
        GSORT.displayColumnKey("${GSORT.displayColumnPrefix}year") shouldBe "year"
        // A column named like a fixed one still decodes to its own name -- the namespacing is what prevents the
        // collision (issue #666 review): `display_updated` is the column `updated`, not the protocol date column.
        GSORT.displayColumnKey("${GSORT.displayColumnPrefix}${GSORT.updated}") shouldBe GSORT.updated
        // A bare fixed column, or a bare name, is not a display key.
        GSORT.displayColumnKey(GSORT.updated) shouldBe null
        GSORT.displayColumnKey("year") shouldBe null
        // The prefix alone names no column.
        GSORT.displayColumnKey(GSORT.displayColumnPrefix) shouldBe null
    }

})
