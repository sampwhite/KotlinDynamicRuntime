package com.dynamicruntime.common.datafile

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.filekind.FKIND
import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.resolveWriteFunctions
import com.dynamicruntime.common.schema.schemaDefs
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * How a `summarizeTable` usage (issue #1883) is held to the type it is on: a table field, a column of its rows, a
 * combine the column allows, and a derived target whose type holds what the combine gives. Pure: parsed types and a
 * plain context, no node. Storing a summary on a save is `DataTableSummaryTest`'s.
 */
class SummarizeTableTest : StringSpec({
    val cxt = KdrCxt.mkSimpleCxt("summarizeTableTest")
    val creations = mapOf(STF.fn to SummarizeTableCreation)

    /** A claim with a table of lines, derived targets of several kinds, and [usages] as its `g-onWrite`. */
    fun dataType(vararg usages: Map<String, Any?>): SchType = parseSchemaTypes(
        schemaDefs(cxt, "t") {
            type("Line") {
                type = SCT.kObject
                property("day", "The day.") { dayOnlyDate() }
                property("note", "A note.")
                property("amount", "How much.") { type = SCT.number }
                property("count", "How many.") { type = SCT.integer }
                property("receipt", "Whether there is one.") { type = SCT.boolean }
            }
            type("Claim") {
                type = SCT.kObject
                property("lines", "The lines.") { dataTable("Line") }
                property("photo", "A photo.") { dataFile(FKIND.png) }
                property("total", "Summed.") {
                    type = SCT.number
                    derived = true
                }
                property("whole", "Summed, whole.") {
                    type = SCT.integer
                    derived = true
                }
                property("lastDay", "The latest day.") {
                    dayOnlyDate()
                    derived = true
                }
                property("typed", "Not derived.") { type = SCT.number }
                onWrite(*usages)
            }
        },
    ).getValue("t.Claim")

    fun problems(vararg usages: Map<String, Any?>) = resolveWriteFunctions(cxt, creations, dataType(*usages)).mapNotNull { it.problem }

    "a summary fitting its table and its target resolves" {
        problems(
            summarizeTable("total", "lines", ReportCombine.sum, "amount"),
            summarizeTable("whole", "lines", ReportCombine.count),
            summarizeTable("lastDay", "lines", ReportCombine.max, "day", priority = 2),
        ).shouldBeEmpty()
        // A sum of whole numbers, and their least, are whole; a count of a column counts its cells.
        problems(summarizeTable("whole", "lines", ReportCombine.sum, "count")).shouldBeEmpty()
        problems(summarizeTable("whole", "lines", ReportCombine.count, "note")).shouldBeEmpty()
    }

    "it reads its table and writes its target, as the type's checks see it" {
        val function = resolveWriteFunctions(cxt, creations, dataType(summarizeTable("total", "lines", ReportCombine.sum, "amount")))
            .single().function!!
        function.reads shouldBe setOf("lines")
        function.writes shouldBe setOf("total")
    }

    "the table must be a table field, and the column one of its rows'" {
        problems(summarizeTable("total", "photo", ReportCombine.count)).single() shouldContain "no table field 'photo'"
        problems(summarizeTable("total", "missing", ReportCombine.count)).single() shouldContain "no table field 'missing'"
        problems(summarizeTable("total", "lines", ReportCombine.sum, "tip")).single() shouldContain
            "no column 'tip'. Its columns are: day, note, amount, count, receipt."
    }

    "the combine must be one the column allows" {
        problems(summarizeTable("total", "lines", ReportCombine.sum)).single() shouldContain "needs a column"
        problems(summarizeTable("total", "lines", ReportCombine.sum, "note")).single() shouldContain
            "does not sum up the string column 'note'"
        problems(summarizeTable("total", "lines", ReportCombine.max, "receipt")).single() shouldContain "boolean column"
        // A report's other rollups are not a column's.
        problems(mapOf("fn" to STF.fn, STF.target to "total", STF.table to "lines", STF.combine to "list")).single() shouldContain
            "combine: 'list' is not a valid option"
    }

    "the target must be derived and hold what the combine gives" {
        problems(summarizeTable("typed", "lines", ReportCombine.sum, "amount")).single() shouldContain "is not 'g-derived'"
        problems(summarizeTable("nothing", "lines", ReportCombine.count)).single() shouldContain "no property 'nothing'"
        // An average, or a sum of decimals, has a fraction an integer cannot hold.
        problems(summarizeTable("whole", "lines", ReportCombine.avg, "count")).single() shouldContain "needs a number"
        problems(summarizeTable("whole", "lines", ReportCombine.sum, "amount")).single() shouldContain "needs a number"
        problems(summarizeTable("total", "lines", ReportCombine.max, "day")).single() shouldContain "needs a day"
        problems(summarizeTable("lastDay", "lines", ReportCombine.max, "note")).single() shouldContain "needs a plain string"
    }
})
