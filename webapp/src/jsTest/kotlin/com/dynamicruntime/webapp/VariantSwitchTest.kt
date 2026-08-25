package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-logic coverage (issue #161) for what [SchemaForm] keeps when a discriminated union switches branch
 * ([valuesAfterBranchSwitch] / [fieldCarriesAcrossBranches], issue #417).
 *
 * The report this fixes: editing a form, a section switched from one trait to another kept the old trait's
 * `data` sub-object — a field named `data` on both branches but a *different shape* on each — so the payload
 * carried the old trait's keys against a new branch that forbids them, and the errors ("Additional property
 * 'topic' is not allowed") named fields the form was no longer even drawing. Same-named same-typed envelope
 * fields (a verb, an id) must still carry across, so switching does not blank everything beside the choice.
 *
 * Generic on purpose — this is a display-engine rule, not a gedra one — but shaped like the edit union that
 * exposed it: two branches, shared scalar envelope fields, and a branch-specific `data` object.
 */
class VariantSwitchTest {

    /** A branch-specific data object: [name] the type, [props] its fields. */
    private fun dataType(props: Map<String, Any?>): Map<String, Any?> = mapOf(
        SCH.type to SCT.kObject,
        SCH.properties to props,
    )

    /** One union branch: its discriminator const [kind], the shared scalar envelope, and a `data` of [dataRef]. */
    private fun branch(kind: String, dataRef: String): Map<String, Any?> = mapOf(
        SCH.type to SCT.kObject,
        SCH.properties to mapOf(
            "kind" to mapOf(SCH.type to SCT.string, SCH.const to kind),
            "action" to mapOf(SCH.type to SCT.string),
            "entryId" to mapOf(SCH.type to SCT.string),
            "data" to mapOf(SCH.dRef to dataRef),
        ),
    )

    /**
     * A union of two branches whose `data` shapes differ: `alpha` carries `{topic, explanation}`, `beta` carries
     * `{year}`. Pass [betaData] to point beta's `data` at a different type name to make the two shapes agree.
     */
    private fun unionType(betaData: String = "t.BetaData"): SchType {
        val defs = mapOf(
            "t.AlphaData" to dataType(
                mapOf("topic" to mapOf(SCH.type to SCT.string), "explanation" to mapOf(SCH.type to SCT.string)),
            ),
            "t.BetaData" to dataType(mapOf("year" to mapOf(SCH.type to SCT.integer))),
            "t.Alpha" to branch("alpha", "t.AlphaData"),
            "t.Beta" to branch("beta", betaData),
            "t.Edit" to mapOf(
                SCH.oneOf to listOf(mapOf(SCH.dRef to "t.Alpha"), mapOf(SCH.dRef to "t.Beta")),
                SCH.discriminator to mapOf(SCH.propertyName to "kind"),
            ),
        )
        return parseSchemaTypes(defs).getValue("t.Edit")
    }

    /**
     * The reported bug: switching branch drops the old branch's `data`, whose keys the new branch would reject,
     * while the shared scalar envelope (`action`, `entryId`) carries across so the switch is not a full reset.
     */
    @Test
    fun switchingBranchDropsTheOldBranchSpecificData() {
        val variants = unionType().variants!!
        val from = variants.select("alpha")
        val to = variants.select("beta")
        val values = mapOf(
            "kind" to "alpha",
            "action" to "addOrReplace",
            "entryId" to "e1",
            "data" to mapOf("topic" to "expenses Q1", "explanation" to "misc"),
        )

        val result = valuesAfterBranchSwitch(values, "kind", from, to, "beta")

        assertEquals("beta", result["kind"])
        assertEquals("addOrReplace", result["action"])
        assertEquals("e1", result["entryId"])
        // The old trait's data would fail beta's closed schema and is not on screen to fix -- so it is dropped.
        assertFalse(result.containsKey("data"), "stale branch-specific data must not carry across")
    }

    /** A same-named field of the *same* type does carry across, so a shared sub-object is not needlessly reset. */
    @Test
    fun switchingBranchKeepsAStructuredFieldOfTheSameType() {
        // Both branches' `data` point at t.AlphaData, so the shape agrees and the value is safe to keep.
        val variants = unionType(betaData = "t.AlphaData").variants!!
        val from = variants.select("alpha")
        val to = variants.select("beta")
        val values = mapOf("kind" to "alpha", "data" to mapOf("topic" to "kept"))

        val result = valuesAfterBranchSwitch(values, "kind", from, to, "beta")

        assertEquals(mapOf("topic" to "kept"), result["data"])
    }

    /** A choice that names no branch (or a cleared one) keeps nothing but the discriminator it just set. */
    @Test
    fun clearingTheChoiceKeepsOnlyTheDiscriminator() {
        val variants = unionType().variants!!
        val from = variants.select("alpha")
        val values = mapOf("kind" to "alpha", "action" to "addOrReplace", "data" to mapOf("topic" to "x"))

        val result = valuesAfterBranchSwitch(values, "kind", from, to = null, picked = null)

        assertEquals(setOf("kind"), result.keys)
        assertEquals(null, result["kind"])
    }

    /** [fieldCarriesAcrossBranches] directly: a scalar carries on matching JSON type; two data shapes do not. */
    @Test
    fun theCarryRuleSeparatesScalarsFromDifferingObjects() {
        val variants = unionType().variants!!
        val alpha = variants.select("alpha")!!
        val beta = variants.select("beta")!!

        val alphaAction = alpha.properties.getValue("action").valueType
        val betaAction = beta.properties.getValue("action").valueType
        assertTrue(fieldCarriesAcrossBranches(alphaAction, betaAction), "shared scalars carry across")

        val alphaData = alpha.properties.getValue("data").valueType
        val betaData = beta.properties.getValue("data").valueType
        assertFalse(fieldCarriesAcrossBranches(alphaData, betaData), "differently shaped objects do not")
    }
}
