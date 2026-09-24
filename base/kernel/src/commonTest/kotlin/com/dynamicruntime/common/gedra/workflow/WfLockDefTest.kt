package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.exception.KdrException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A normal workflow's trait locks (issue #857) as definition data: declared, parsed and round-tripped, and refused
 * where they could not mean anything -- outside a normal workflow, locking a trait twice, or naming a task that does
 * not own the trait or says nothing about who may save it (a lock holding for nobody).
 */
class WfLockDefTest {
    private val cxt: KdrCxtBase = LiteCxt()

    private fun def(entry: WfEntry = WfEntry.normal, saveRule: String? = WFC.reviewer, locks: WfDefBuilder.() -> Unit) =
        WfDefBuilder("audit", entry).apply {
            task("record", "Record") {
                trait("audit")
                save("s", "S", WfSaveKind.edit)
                saveRule?.let { saveWhen(it) }
            }
            task("other", "Other") { trait("notes"); save("s2", "S2", WfSaveKind.edit) }
            locks()
        }.build()

    @Test
    fun aLockParsesAndRoundTrips() {
        val parsed = parseWfDef(cxt, def { lock("audit", writableVia = "record", overrideWhen = "hasAdminLevel") })
        val lock = parsed.locks.single()
        assertEquals(listOf("audit", "#always", "record", "hasAdminLevel"), listOf(lock.traitId, lock.whenExpr, lock.writableVia, lock.overrideWhen))
        val again = parseWfDef(cxt, parsed.toJsonMap()).locks.single()
        assertEquals(listOf(lock.traitId, lock.whenExpr, lock.writableVia, lock.overrideWhen), listOf(again.traitId, again.whenExpr, again.writableVia, again.overrideWhen))
        // No override rule: nobody may override.
        assertNull(parseWfDef(cxt, def { lock("audit", writableVia = "record") }).locks.single().overrideWhen)
    }

    @Test
    fun aLockMustBeOwnedByATaskThatSaysWhoMaySave() {
        // The named task does not collect the trait.
        assertFailsWith<KdrException> { parseWfDef(cxt, def { lock("audit", writableVia = "other") }) }
        // No such task.
        assertFailsWith<KdrException> { parseWfDef(cxt, def { lock("audit", writableVia = "nope") }) }
        // A task anyone may save exempts everyone, so the lock would hold for nobody.
        assertFailsWith<KdrException> { parseWfDef(cxt, def(saveRule = null) { lock("audit", writableVia = "record") }) }
        // One lock per trait.
        assertFailsWith<KdrException> {
            parseWfDef(cxt, def { lock("audit", writableVia = "record"); lock("audit", writableVia = "record", whenCfacts = "x") })
        }
    }
}
