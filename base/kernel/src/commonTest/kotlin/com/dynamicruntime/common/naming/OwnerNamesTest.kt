package com.dynamicruntime.common.naming

import com.dynamicruntime.common.cfact.CFactParser
import com.dynamicruntime.common.cfact.isCFactName
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The owner-name rules (issue #921), in `commonTest` because the frontend and the backend both judge names by
 * them: a client's own names are bare, a global one is `<root>:<local>`, and each kind holds its local part to its
 * own rule.
 */
class OwnerNamesTest {

    @Test
    fun clientNamesAreBare() {
        for (kind in OwnedNameKind.entries) {
            assertNull(clientNameProblem(kind, "survey1"), "a bare $kind name is a client's own")
            val colon = assertNotNull(clientNameProblem(kind, "kdr:survey1"))
            assertTrue("another owner's definition" in colon, colon)
        }
    }

    @Test
    fun eachKindHoldsItsLocalPartToItsOwnRule() {
        // A dot is a path to a data tool, so no trait id holds one; a cfact name may.
        assertNotNull(clientNameProblem(OwnedNameKind.trait, "expense.report"))
        assertNull(clientNameProblem(OwnedNameKind.cfact, "audit.approved"))
        // Workflow and task ids are variable names.
        assertNotNull(clientNameProblem(OwnedNameKind.task, "review-1"))
        assertNotNull(clientNameProblem(OwnedNameKind.task, "1review"))
        assertNull(clientNameProblem(OwnedNameKind.task, "_review1"))
        assertNotNull(clientNameProblem(OwnedNameKind.workflow, "create form"))
        for (kind in OwnedNameKind.entries) assertNotNull(clientNameProblem(kind, ""))
    }

    @Test
    fun aRootedNameHasOneColonAndAnOwnerRoot() {
        assertNull(rootedNameProblem(OwnedNameKind.trait, "kdr:expenseReport"))
        assertNull(rootedNameProblem(OwnedNameKind.cfact, "abc2:finished"))
        assertNotNull(rootedNameProblem(OwnedNameKind.trait, "expenseReport"))
        assertNotNull(rootedNameProblem(OwnedNameKind.trait, "kdr:a:b"))
        // A root is letters and digits, starting with a letter.
        assertNotNull(rootedNameProblem(OwnedNameKind.cfact, "2kdr:finished"))
        assertNotNull(rootedNameProblem(OwnedNameKind.cfact, "k_dr:finished"))
        assertNotNull(rootedNameProblem(OwnedNameKind.cfact, ":finished"))
        // The local part keeps its kind's rule.
        assertNotNull(rootedNameProblem(OwnedNameKind.trait, "kdr:expense.report"))
        assertNotNull(rootedNameProblem(OwnedNameKind.task, "kdr:"))
        assertTrue(isOwnerRoot("kdr") && isOwnerRoot("abc2") && !isOwnerRoot("") && !isOwnerRoot("a-b"))
    }

    @Test
    fun aNameIsWellFormedBareOrRooted() {
        assertTrue(isOwnedName(OwnedNameKind.task, "review"))
        assertTrue(isOwnedName(OwnedNameKind.task, "kdr:review"))
        assertFalse(isOwnedName(OwnedNameKind.task, "kdr:review:again"))
        assertFalse(isOwnedName(OwnedNameKind.trait, "kdr:"))
    }

    // A client's namespace is fixed by its id (issue #949), and a sandbox's is its parent's.
    @Test
    fun aClientNamespaceIsItsOwnOrBeneathIt() {
        assertTrue(clientNamespace("acme") == "client.acme")
        assertTrue(clientNamespace("acme:sandbox") == "client.acme")
        assertTrue(isClientNamespace("client.acme", "acme"))
        assertTrue(isClientNamespace("client.acme.forms", "acme"))
        assertTrue(isClientNamespace("client.acme", "acme:sandbox"))
        // A lookalike prefix is somebody else's, and so is everything outside the root.
        assertFalse(isClientNamespace("client.acmex", "acme"))
        assertFalse(isClientNamespace("client", "acme"))
        assertFalse(isClientNamespace("acmeconfig", "acme"))
        assertNull(clientNamespaceProblem("client.acme.forms", "acme"))
        assertNotNull(clientNamespaceProblem("globalconfig", "acme"))
    }

    // The cfact grammar takes a rooted name as one atom: the colon is none of its operators.
    @Test
    fun aCfactExpressionReadsARootedName() {
        assertTrue(isCFactName("kdr:isCta"))
        assertTrue(isCFactName("loggedIn"))
        assertFalse(isCFactName("kdr:is:cta"))
        val allowed = setOf("kdr:isCta", "kdr:reviewer", "loggedIn")
        val parsed = CFactParser.parse("(kdr:isCta,~kdr:reviewer)|loggedIn", allowed)
        assertTrue(parsed.matches(setOf("kdr:isCta")))
        assertFalse(parsed.matches(setOf("kdr:isCta", "kdr:reviewer")))
        assertTrue(parsed.matches(setOf("loggedIn")))
    }
}
