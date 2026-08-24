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
     * The per-row user admission (issue #411): the brute-force user search checks each row against the scope
     * with this, rather than composing an SQL predicate. It is the three constraints together -- client, the
     * lenient org, and owner -- so it must agree with the widths above.
     */
    @Test
    fun admitsUserRowAppliesEveryConstraint() {
        // Unrestricted admits anyone.
        assertTrue(ReadScope.unrestricted.admitsUserRow("acme", "eng", 1L))
        // Client-confined: own client yes, another no; the lenient org still applies within it.
        val client = ReadScope.ofClient("acme")
        assertTrue(client.admitsUserRow("acme", "eng", 1L))
        assertTrue(client.admitsUserRow("acme", null, 2L))
        assertFalse(client.admitsUserRow("globex", "eng", 3L))
        // Org-confined: own org, and the client's org-less rows, but not another org.
        val org = ReadScope.ofOrg("acme", "eng")
        assertTrue(org.admitsUserRow("acme", "eng", 1L))
        assertTrue(org.admitsUserRow("acme", null, 2L))
        assertFalse(org.admitsUserRow("acme", "sales", 3L))
        // Own-user: only that user's own row.
        val self = ReadScope.ofUser(7L)
        assertTrue(self.admitsUserRow("acme", "eng", 7L))
        assertFalse(self.admitsUserRow("acme", "eng", 8L))
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
