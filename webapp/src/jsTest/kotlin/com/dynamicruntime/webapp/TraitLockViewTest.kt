package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WVF
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Trait locks on the page side (issue #857): reading the locks the backend reports, and the raw editor's handling of
 * a locked section -- left out when untouched, reported when changed -- and the override on the patch.
 */
class TraitLockViewTest {
    @Test
    fun locksParse() {
        val locks = parseTraitLocks(
            listOf(
                mapOf(WFD.traitId to "acmeSiteAudit", WFD.workflowId to "auditReview", WFD.label to "Audit review", WVF.canOverride to true),
                // No trait: nothing to lock.
                mapOf(WFD.workflowId to "x"),
            ),
        )
        val lock = locks.single()
        assertEquals(listOf("acmeSiteAudit", "auditReview", "Audit review"), listOf(lock.traitId, lock.workflowId, lock.label))
        assertTrue(lock.canOverride)
    }

    private fun edit(traitId: String, value: String) =
        mapOf(GED.action to "addOrReplace", GE.traitId to traitId, GE.data to mapOf("v" to value))

    private fun target(vararg edits: Map<String, Any?>) = mapOf(GDF.gedraId to "g1", GPF.edits to edits.toList())

    @Test
    fun anUntouchedLockedSectionIsLeftOutAndAChangedOneReported() {
        val seeded = target(edit("audit", "a"), edit("notes", "n"))
        // Untouched: dropped, so it cannot trip its lock; the unlocked section rides along as ever.
        val untouched = splitLockedEdits(target(edit("audit", "a"), edit("notes", "n2")), seeded, setOf("audit"))
        assertEquals(listOf(edit("notes", "n2")), untouched.target[GPF.edits])
        assertEquals(emptyList(), untouched.changedLocked)
        // Changed: kept, and named -- only an override may send it.
        val changed = splitLockedEdits(target(edit("audit", "b"), edit("notes", "n")), seeded, setOf("audit"))
        assertEquals(listOf("audit"), changed.changedLocked)
        assertEquals(2, (changed.target[GPF.edits] as List<*>).size)
        // Nothing locked: the target as it was.
        assertEquals(seeded, splitLockedEdits(seeded, seeded, emptySet()).target)
    }

    @Test
    fun anOverrideRidesThePatchOnlyWhenGiven() {
        assertEquals("typo", formDocPatchBody(target(), overrideReason = "typo")[GPF.overrideReason])
        assertFalse(formDocPatchBody(target()).containsKey(GPF.overrideReason))
        assertNull(formDocPatchBody(target())[GPF.overrideReason])
    }
}
