package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.exception.KdrException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A normal workflow's eligibility tests as definition data (issue #783): declared in order by the builder,
 * parsed into [WfEligibility], round-tripped through `toJsonMap`, and refused where they have no coherent
 * reading. Evaluating them against a form's cfacts is `base:common`'s, where the cfact registry lives.
 */
class WfEligibilityDefTest {
    private val cxt: KdrCxtBase = LiteCxt()

    private fun normal(extra: WfDefBuilder.() -> Unit) = WfDefBuilder("audit", WfEntry.normal).apply {
        task("t", "T") { trait("a"); save("s", "S", WfSaveKind.edit) }
        extra()
    }.build()

    @Test
    fun eligibilityParsesInOrderAndRoundTrips() {
        val def = parseWfDef(
            cxt,
            normal {
                label = "Audit"
                eligibility("surveyDone", "surveyComplete", "Finish the survey first.")
                eligibility("notAudited", "~underAudit", "An audit is already open.")
            },
        )
        assertEquals(listOf("surveyDone", "notAudited"), def.eligibility.map { it.id })
        assertEquals("~underAudit", def.eligibility[1].test)

        val again = parseWfDef(cxt, def.toJsonMap())
        assertEquals(def.eligibility.map { Triple(it.id, it.test, it.explanation) }, again.eligibility.map { Triple(it.id, it.test, it.explanation) })
        // The workflow's own label survives the round trip too -- it used to be dropped on serialization.
        assertEquals("Audit", again.label)
    }

    @Test
    fun aWorkflowWithNoEligibilityIsEligibleByDefinition() {
        assertTrue(parseWfDef(cxt, normal {}).eligibility.isEmpty())
    }

    @Test
    fun twoTestsWithOneIdAreRefused() {
        val e = assertFailsWith<KdrException> {
            parseWfDef(cxt, normal {
                eligibility("dup", "a", "A")
                eligibility("dup", "b", "B")
            })
        }
        assertTrue(e.message.orEmpty().contains("'dup'"))
    }

    @Test
    fun aBlankTestIsRefused() {
        assertFailsWith<KdrException> { parseWfDef(cxt, normal { eligibility("blank", " ", "Never shown.") }) }
    }

    @Test
    fun onlyANormalWorkflowHasEligibility() {
        val raw = WfDefBuilder("review", WfEntry.survey).apply {
            eligibility("x", "a", "A")
            task("t", "T") { trait("a"); save("s", "S", WfSaveKind.edit) }
        }.build()
        val e = assertFailsWith<KdrException> { parseWfDef(cxt, raw) }
        assertTrue(e.message.orEmpty().contains("only a normal workflow"))
    }
}
