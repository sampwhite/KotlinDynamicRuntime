package com.dynamicruntime.common.schema

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.Parsed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Layouts and narrowing as reports (issue #909): each problem is coded and located within the block or type it is
 * in, with the sentence the boot has always printed. In `commonTest`, so backend and browser agree.
 */
class SchLayoutReportTest {
    private val where = "Type 't.A'"

    private fun failed(raw: Map<String, Any?>) = assertIs<Parsed.Failed>(parseSchLayoutResult(where, raw)).problems

    /** An object type `t.A` with [props], parsed. */
    private fun objType(vararg props: Pair<String, Map<String, Any?>>) =
        parseSchemaTypes(mapOf("t.A" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf(*props))))
            .getValue("t.A")

    private fun layoutOf(vararg fields: Map<String, Any?>) =
        parseSchLayout(where, mapOf(SL.schemaFields to fields.toList()))

    @Test
    fun aLayoutBlockReportsEveryProblemAtItsPlace() {
        val raw = mapOf(
            SL.schemaFields to listOf(
                mapOf(SL.field to "a", "lable" to "A"),
                mapOf(SL.label to "no field"),
                mapOf(SL.field to "c", SL.defaultMode to "sideways"),
            ),
            SL.strings to mapOf(LAYSTR.formErrorHint to 5),
            SL.mode to "loose",
        )
        val problems = failed(raw)
        assertEquals(
            listOf(
                LayoutError.unknownKey, LayoutError.noFieldName, LayoutError.badValue, LayoutError.badValue,
                LayoutError.badValue,
            ),
            problems.map { it.code },
        )
        assertEquals(
            listOf(
                "schemaFields[0]", "schemaFields[1]", "schemaFields[2].defaultMode",
                "strings.${LAYSTR.formErrorHint}", "mode",
            ),
            problems.map { it.location?.path },
        )
        // The throwing form raises the first, word for word.
        val thrown = assertFailsWith<KdrException> { parseSchLayout(where, raw) }
        assertEquals(problems.first().message, thrown.message)
    }

    @Test
    fun aNestedLayoutIsLocatedWhereItSits() {
        val body = mapOf(SCH.properties to mapOf("inner" to mapOf(SCH.layout to mapOf<String, Any?>())))
        val problem = assertIs<Parsed.Failed>(parseTypeLayout("t.A", body)).problems.single()
        assertEquals(LayoutError.nestedLayout, problem.code)
        assertEquals("properties.inner", problem.location?.path)
    }

    @Test
    fun aTemplateFaultCarriesItsFieldAndItsOffset() {
        val type = objType("n" to mapOf(SCH.type to SCT.integer))
        val layout = layoutOf(mapOf(SL.field to "n", SL.hint to $$"at most ${max}"))
        val problem = layoutTemplateProblems(where, layout, type).single()
        assertEquals(LayoutError.unknownParam, problem.code)
        assertEquals("schemaFields[0].hint", problem.location?.path)

        val broken = layoutOf(mapOf(SL.field to "n", SL.label to $$"Count ${n"))
        val malformed = layoutTemplateProblems(where, broken, type).single()
        assertEquals(LayoutError.malformedTemplate, malformed.code)
        assertEquals("schemaFields[0].label", malformed.location?.path)
        assertEquals(6, malformed.location?.offset)
    }

    @Test
    fun anEntryLabelHeadsAListOfEntriesAndIsGivenOnlyItsNumber() {
        // Issue #1714: what a form heads each entry of a list with, a template over the entry's number.
        val type = objType(
            "speakers" to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.type to SCT.kObject)),
            "topics" to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.type to SCT.string, SCH.options to listOf("a", "b"))),
            "title" to mapOf(SCH.type to SCT.string),
        )
        val good = layoutOf(mapOf(SL.field to "speakers", SL.entryLabel to $$"Co-speaker ${n}"))
        assertEquals($$"Co-speaker ${n}", good.fields.single().entryLabel)
        assertEquals($$"Co-speaker ${n}", good.fields.single().toJsonMap()[SL.entryLabel])
        // Read by its slot's name, as Design View reads a field's copy (issue #1748); a name that is no copy reads nothing.
        assertEquals($$"Co-speaker ${n}", good.fields.single().copyIn(SL.entryLabel))
        assertEquals(null, good.fields.single().copyIn(SL.hint))
        assertEquals(null, good.fields.single().copyIn(SL.required))
        assertEquals(emptyList(), layoutFieldProblems(where, good, type) + layoutTemplateProblems(where, good, type))

        // Only a list drawn entry by entry: neither a plain field nor a list of choices, which is one control.
        val notLists = layoutOf(
            mapOf(SL.field to "title", SL.entryLabel to "Line"),
            mapOf(SL.field to "topics", SL.entryLabel to "Topic"),
        )
        val misplaced = layoutFieldProblems(where, notLists, type)
        assertEquals(listOf(LayoutError.entryLabelNotList, LayoutError.entryLabelNotList), misplaced.map { it.code })
        assertEquals(listOf("schemaFields[0].entryLabel", "schemaFields[1].entryLabel"), misplaced.map { it.location?.path })

        // Given its number alone: anything else would render as written.
        val otherParam = layoutOf(mapOf(SL.field to "speakers", SL.entryLabel to $$"Speaker ${name}"))
        val problem = layoutTemplateProblems(where, otherParam, type).single()
        assertEquals(LayoutError.unknownParam, problem.code)
        assertEquals("schemaFields[0].entryLabel", problem.location?.path)
    }

    @Test
    fun aFieldTheTypeLacksIsLocatedAtItsEntry() {
        val layout = layoutOf(mapOf(SL.field to "a"), mapOf(SL.field to "b"))
        val problem = layoutFieldProblems(where, layout, objType("a" to emptyMap())).single()
        assertEquals(LayoutError.undeclaredField, problem.code)
        assertEquals("schemaFields[1].field", problem.location?.path)
    }

    @Test
    fun aWideningIsCodedAndLocatedAtTheKeywordInTheType() {
        val base = mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf("kind" to mapOf(SCH.type to SCT.string, SCH.options to listOf("a", "b"))),
            SCH.required to listOf("kind"),
        )
        val kind = mapOf(SCH.type to SCT.string, SCH.options to listOf("a", "c"))
        val overlay = mapOf(SCH.properties to mapOf("kind" to kind))
        val problem = narrowingProblems("t.A", base, overlay).single()
        assertEquals(NarrowingError.addsChoice, problem.code)
        assertEquals("t.A.properties.kind.${SCH.options}", problem.location?.path)
        assertTrue("'t.A.kind'" in problem.message)
    }
}
