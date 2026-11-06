package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.exception.KdrException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A normal workflow's singleton-cfact rules as definition data (issue #784): parsed, round-tripped, and held
 * to the framework's hardwired list -- a workflow cannot make up a singleton cfact, since each one carries code
 * behavior. Evaluating a rule is `base:common`'s, where the cfact registry lives.
 */
class WfSingletonDefTest {
    private val cxt: KdrCxtBase = LiteCxt()

    private fun normal(extra: WfDefBuilder.() -> Unit) = WfDefBuilder("audit", WfEntry.normal).apply {
        task("t", "T") { trait("a"); save("s", "S", WfSaveKind.edit) }
        extra()
    }.build()

    @Test
    fun rulesParseAndRoundTrip() {
        val def = parseWfDef(
            cxt,
            normal {
                singleton(WSC.ownerOnly, "awaitingApproval")
                singleton(WSC.finished, "approved")
            },
        )
        assertEquals(listOf(WSC.ownerOnly, WSC.finished), def.singletons.map { it.cfact })
        val again = parseWfDef(cxt, def.toJsonMap())
        assertEquals(def.singletons.map { it.cfact to it.whenExpr }, again.singletons.map { it.cfact to it.whenExpr })
    }

    @Test
    fun onlyAFrameworkSingletonMayBeEmitted() {
        val e = assertFailsWith<KdrException> { parseWfDef(cxt, normal { singleton("madeUp", "a") }) }
        assertTrue(e.message.orEmpty().contains("'madeUp'"))
    }

    /** Needs Review is the `review` keyword's to set (issue #1596): a workflow's rule may not, and is told what to do instead. */
    @Test
    fun aKeywordsStatusIsNotAWorkflowsToDeclare() {
        val e = assertFailsWith<KdrException> { parseWfDef(cxt, normal { singleton(WSC.needsReview, "a") }) }
        assertTrue(e.message.orEmpty().contains("which the '${CTAK.review}' search keyword sets"), e.message)
    }

    @Test
    fun oneRulePerSingletonAndAConditionEach() {
        assertFailsWith<KdrException> {
            parseWfDef(cxt, normal {
                singleton(WSC.finished, "a")
                singleton(WSC.finished, "b")
            })
        }
        assertFailsWith<KdrException> { parseWfDef(cxt, normal { singleton(WSC.finished, " ") }) }
    }

    @Test
    fun onlyANormalWorkflowEmitsSingletons() {
        val raw = WfDefBuilder("review", WfEntry.coreDetails).apply {
            singleton(WSC.finished, "a")
            task("t", "T") { trait("a"); save("s", "S", WfSaveKind.edit) }
        }.build()
        assertFailsWith<KdrException> { parseWfDef(cxt, raw) }
    }
}
