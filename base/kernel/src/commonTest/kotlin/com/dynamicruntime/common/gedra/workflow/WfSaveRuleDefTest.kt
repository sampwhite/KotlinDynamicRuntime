package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.exception.KdrException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A task's rule for who may save it (issue #856) as definition data: declared, parsed and round-tripped, and refused
 * where it could not mean anything -- outside a normal workflow, blank, or on a task that offers no save.
 */
class WfSaveRuleDefTest {
    private val cxt: KdrCxtBase = LiteCxt()

    private fun normal(entry: WfEntry = WfEntry.normal, rule: String = WFC.reviewer, saves: Boolean = true) =
        WfDefBuilder("audit", entry).apply {
            task("record", "Record") {
                trait("a")
                if (saves) save("s", "S", WfSaveKind.edit)
                saveWhen(rule)
            }
            task("other", "Other") { trait("b"); save("s2", "S2", WfSaveKind.edit) }
        }.build()

    @Test
    fun aRuleParsesAndRoundTrips() {
        val def = parseWfDef(cxt, normal())
        assertEquals(WFC.reviewer, def.task("record")?.saveWhen)
        // A task that says nothing admits anyone who can see the form.
        assertNull(def.task("other")?.saveWhen)
        assertEquals(WFC.reviewer, parseWfDef(cxt, def.toJsonMap()).task("record")?.saveWhen)
    }

    @Test
    fun aSurveyTaskMayNotDeclareOne() {
        assertFailsWith<KdrException> { parseWfDef(cxt, normal(entry = WfEntry.survey)) }
    }

    @Test
    fun aBlankRuleReadsAsNone() {
        // The schema layer reads a blank optional string as absent, so a blank rule means what leaving it out does.
        assertNull(parseWfDef(cxt, normal(rule = " ")).task("record")?.saveWhen)
    }

    @Test
    fun aTaskWithNoSaveMayNotDeclareOne() {
        // A rule on a task with nothing to save would guard nothing.
        assertFailsWith<KdrException> {
            parseWfDef(
                cxt,
                WfDefBuilder("audit", WfEntry.normal).apply {
                    task("approve", "Approve") { approval("x", "P", "B"); saveWhen(WFC.reviewer) }
                }.build(),
            )
        }
    }
}
