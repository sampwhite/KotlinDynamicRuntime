package com.dynamicruntime.common.schema

import com.dynamicruntime.common.exception.KdrException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A schema document as a report (issue #909): a fault comes back coded and located at its place in the document,
 * with the same sentence the throwing [parseSchemaTypes] raises. In `commonTest`, so backend and browser agree.
 */
class SchReportTest {
    private fun obj(vararg props: Pair<String, Any?>) =
        mapOf(SCH.type to SCT.kObject, SCH.properties to linkedMapOf(*props))

    private fun fault(defs: Map<String, Any?>) =
        analyzeSchemaTypes(defs).also { assertNull(it.types) }.problems.single()

    @Test
    fun aSoundDocumentReportsItsTypes() {
        val report = analyzeSchemaTypes(mapOf("t.A" to obj("x" to mapOf(SCH.type to SCT.string))))
        assertTrue(report.problems.isEmpty())
        assertNotNull(report.types?.get("t.A")?.properties?.get("x"))
    }

    @Test
    fun aFaultIsLocatedDownToTheKeyItIsUnder() {
        val inner = obj("v" to mapOf(SCH.enum to listOf("a")))
        val nested = obj("list" to mapOf(SCH.type to SCT.array, SCH.items to inner))
        val problem = fault(mapOf("t.A" to mapOf<String, Any?>(SCH.type to SCT.string), "t.B" to nested))
        assertEquals(SchemaError.refusedKeyword, problem.code)
        assertEquals("t.B.properties.list.items.properties.v", problem.location?.path)
        // The sentence is the throwing form's, word for word.
        val thrown = assertFailsWith<KdrException> { parseSchemaTypes(mapOf("t.B" to nested)) }
        assertEquals(thrown.message, problem.message)
    }

    @Test
    fun eachKindOfFaultHasItsCode() {
        val string = mapOf<String, Any?>(SCH.type to SCT.string)
        assertEquals(SchemaError.unknownKeyword, fault(mapOf("t.A" to string + ("g-visibleOnyl" to true))).code)
        assertEquals(SchemaError.badValue, fault(mapOf("t.A" to string + (SCH.uniqueItems to "yes"))).code)
        assertEquals(
            SchemaError.notApplicable,
            fault(mapOf("t.A" to mapOf(SCH.type to SCT.integer, SCH.pattern to "[0-9]"))).code,
        )
        assertEquals(SchemaError.badCondition, fault(mapOf("t.A" to obj() + (SCH.kThen to mapOf<String, Any?>()))).code)
        assertEquals(SchemaError.badUnion, fault(mapOf("t.A" to mapOf(SCH.oneOf to listOf(string)))).code)
    }

    @Test
    fun anUnresolvedRefIsReportedWhereItWasRead() {
        val problem = fault(mapOf("t.A" to obj("owner" to mapOf(SCH.dRef to "#/\$defs/t.Missing"))))
        assertEquals(SchemaError.unknownRef, problem.code)
        assertEquals("t.A.properties.owner", problem.location?.path)
        assertTrue("t.Missing" in problem.message)
    }

    @Test
    fun aBadUnionBranchIsReportedAtTheUnionAndItsBranch() {
        val union = mapOf(
            SCH.oneOf to listOf(obj("kind" to mapOf(SCH.type to SCT.string, SCH.const to "a")), obj("other" to obj())),
            SCH.discriminator to mapOf(SCH.propertyName to "kind"),
        )
        val problem = fault(mapOf("t.U" to union))
        assertEquals(SchemaError.badUnion, problem.code)
        assertEquals("t.U", problem.location?.path)
        assertTrue(problem.message.startsWith("Branch 2"))
        // A fault inside an inline branch is placed in it.
        val inBranch = mapOf(SCH.oneOf to listOf(obj("v" to mapOf(SCH.allOf to listOf<Any>()))))
        assertEquals("t.U.oneOf[0].properties.v", fault(mapOf("t.U" to union + inBranch)).location?.path)
    }

    @Test
    fun aPatternFaultCarriesItsOffsetInThePattern() {
        val problem = fault(mapOf("t.A" to mapOf(SCH.type to SCT.string, SCH.pattern to "ab++")))
        assertEquals(SchemaError.badPattern, problem.code)
        assertEquals("t.A", problem.location?.path)
        assertEquals(3, problem.location?.offset)
        assertTrue("possessive" in problem.message)
    }
}
