package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `buildCfactGate` (issue #564): the frontend `g-visibleWhen` evaluator over the delivered cfacts. Pure -- a
 * `{name -> present}` map in, a predicate out -- reusing the kernel `CFactParser`, so no React and no DOM.
 */
class CfactGateTest {

    // The delivered vocabulary: every frontend cfact, present or not. hasAdminLevel present, hasEnvAuth not.
    private val cfacts = mapOf("hasAdminLevel" to true, "hasEnvAuth" to false)
    private val gate = buildCfactGate(cfacts)

    @Test
    fun aPresentCfactShowsTheFieldAndAnAbsentOneHidesIt() {
        assertTrue(gate("hasAdminLevel"))
        assertFalse(gate("hasEnvAuth"))
    }

    @Test
    fun negationReadsAnAbsentCfactAsTrue() {
        // The case a present-only list could not express: the name is in the vocabulary as absent, so `~` of it
        // is true rather than a parse error on an unknown name.
        assertTrue(gate("~hasEnvAuth"))
        assertFalse(gate("~hasAdminLevel"))
    }

    @Test
    fun compoundExpressionsCombineTheFacts() {
        assertFalse(gate("hasAdminLevel,hasEnvAuth")) // and: hasEnvAuth is absent
        assertTrue(gate("hasAdminLevel|hasEnvAuth")) // or: hasAdminLevel is present
    }

    @Test
    fun noCfactsMeansNoGating() {
        val open = buildCfactGate(null)
        assertTrue(open("hasAdminLevel"))
        assertTrue(open("~anything"))
    }

    @Test
    fun aGateNamingAnUndeliveredCfactFailsOpen() {
        // Not in the delivered vocabulary at all: the parser rejects the name, but visibility is presentation
        // only and the backend enforces, so the field shows rather than vanishing on a technicality.
        assertTrue(gate("somethingNeverDelivered"))
    }
}
