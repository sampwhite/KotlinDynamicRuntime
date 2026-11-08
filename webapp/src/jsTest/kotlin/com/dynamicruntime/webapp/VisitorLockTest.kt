package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A visitor kept out of a demo client's configuration (issue #1668): the overview row's `configLocked` says why, and every
 * editor the page draws reads it -- the bundle editor's gate, the definition editor, publishing and deleting a bundle,
 * and the line saying how a save takes effect -- so the configuration is shown read-only rather than offering edits
 * that fail.
 */
class VisitorLockTest {
    private val why =
        "You joined Conference demo as a visitor, and this deployment does not let visitors change a demo client's configuration."

    private fun row(locked: Boolean): ClientOverview = parseClientOverview(
        listOf(
            buildMap<String, Any?> {
                put(CLD.clientId, "confdemo")
                put(CLD.name, "Conference demo")
                put(CLD.status, ClientStatus.present.name)
                put(CLD.origin, GedraConfigOrigin.stored.name)
                if (locked) put(CLD.configLocked, why)
            },
        ),
    ).single()

    @Test
    fun aLockedRowIsReadNotEdited() {
        val locked = row(locked = true)
        assertEquals(why, locked.configLocked)
        val gate = bundleEditGate(locked)
        assertFalse(gate.editable)
        assertEquals(why, gate.note)
        assertFalse(definitionEditable(locked))
        assertFalse(deletesBundlesHere(locked))
        val draft = ConfigSummaryView("base", 2, false, null, null, 0)
        assertEquals(BundleAction(false, why).note, bundleAction(locked, draft).note)
        assertFalse(bundleAction(locked, draft).publish)
        assertEquals("$why Its configuration is shown here, and not changed.", clientSaveEffectLine(locked, savesDraft = false))
    }

    @Test
    fun anUnlockedRowIsEditedAsBefore() {
        val open = row(locked = false)
        assertEquals(null, open.configLocked)
        assertTrue(bundleEditGate(open).editable)
        assertTrue(definitionEditable(open))
        assertTrue(bundleAction(open, ConfigSummaryView("base", 2, false, null, null, 0)).publish)
    }
}
