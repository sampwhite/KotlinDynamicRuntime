package com.dynamicruntime.common.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The read scope's widths, and in particular the **lenient null** rule for organizations (issue #225): a row
 * with no organization belongs to its client rather than to any organization, and stays visible to everyone in
 * that client.
 *
 * That rule is the one deliberate choice in here, so it is the one worth pinning. Strict matching would make
 * every row written before a client adopted organizations vanish the moment somebody was given a primary one --
 * an adoption cliff indistinguishable from data loss.
 */
class ReadScopeTest {

    @Test
    fun unrestrictedConstrainsNothing() {
        assertTrue(ReadScope.unrestricted.isUnrestricted)
        assertFalse(ReadScope.ofClient("acme").isUnrestricted)
        assertFalse(ReadScope.ofOrg("acme", "eng").isUnrestricted)
        assertFalse(ReadScope.ofUser(1L).isUnrestricted)
    }

    @Test
    fun anUnconfinedScopeAdmitsEveryOrganization() {
        val scope = ReadScope.ofClient("acme")
        assertTrue(scope.admitsOrg(null))
        assertTrue(scope.admitsOrg("eng"))
        assertTrue(scope.admitsOrg("sales"))
    }

    @Test
    fun aConfinedScopeAdmitsItsOwnOrganization() {
        val scope = ReadScope.ofOrg("acme", "eng")
        assertTrue(scope.admitsOrg("eng"))
        assertFalse(scope.admitsOrg("sales"))
    }

    /** The lenient half: content that predates organizations stays visible to the whole client. */
    @Test
    fun aConfinedScopeStillAdmitsRowsWithNoOrganization() {
        assertTrue(ReadScope.ofOrg("acme", "eng").admitsOrg(null))
    }

    /**
     * Statements are cached by name, so the shape key must separate the shapes and merge the values -- two
     * different organizations share one prepared statement, an org scope and a client scope do not.
     */
    @Test
    fun theShapeKeyDistinguishesShapesAndNotValues() {
        assertEquals(ReadScope.ofOrg("acme", "eng").shapeKey, ReadScope.ofOrg("other", "sales").shapeKey)
        assertFalse(ReadScope.ofOrg("acme", "eng").shapeKey == ReadScope.ofClient("acme").shapeKey)
        assertFalse(ReadScope.ofClient("acme").shapeKey == ReadScope.unrestricted.shapeKey)
    }
}
