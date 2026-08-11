package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-logic coverage (issue #161) for [booleanIsTwoState] — which control the endpoint form gives a boolean
 * field (issue #261). Schema in, verdict out: no React, no DOM, no server.
 *
 * The types are built by parsing real schema through the kernel's own [parseSchemaTypes] rather than by
 * constructing a [SchType] by hand, so what is under test is the schema a backend actually writes — including
 * the two things the rule reads, `default` and the parent's `required` list, which sit in different places.
 */
class BooleanWidgetTest {

    /** A one-field object type: `flag`, boolean, with whatever [attrs] and required-ness the case needs. */
    private fun flagType(attrs: Map<String, Any?> = emptyMap(), required: Boolean = false): SchType {
        val schema = mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf("flag" to (mapOf(SCH.type to SCT.boolean, SCH.description to "A flag") + attrs)),
            SCH.required to if (required) listOf("flag") else emptyList(),
        )
        return parseSchemaTypes(mapOf("t.Flags" to schema)).getValue("t.Flags")
    }

    /** [booleanIsTwoState] as the form asks it: the field's type, plus its parent's statement of required. */
    private fun twoState(attrs: Map<String, Any?> = emptyMap(), required: Boolean = false): Boolean {
        val type = flagType(attrs, required)
        return booleanIsTwoState(type.properties.getValue("flag").valueType, "flag" in type.required)
    }

    /**
     * The case the issue is about: a plain optional boolean has three states, because `emptyIsAbsent` keeps
     * absent apart from `false`. A checkbox cannot say which, so this one gets the Select.
     */
    @Test
    fun aPlainOptionalBooleanNeedsThreeStates() {
        assertEquals(false, twoState())
    }

    /** Absent is invalid, so `true` and `false` are the only outcomes: a checkbox says everything there is. */
    @Test
    fun aRequiredBooleanIsTwoState() {
        assertEquals(true, twoState(required = true))
    }

    /** A default is what absent means, so absent is not an outcome of its own — either way round. */
    @Test
    fun aDefaultCollapsesTheThirdState() {
        assertEquals(true, twoState(mapOf(SCH.default to false)))
        assertEquals(true, twoState(mapOf(SCH.default to true)))
    }

    /** Required *and* defaulted is the injection case the validator handles; still two states. */
    @Test
    fun requiredWithADefaultIsTwoState() {
        assertEquals(true, twoState(mapOf(SCH.default to false), required = true))
    }

    /**
     * `default: null` is not a default — the parser drops it (an absent default and a null one are the same
     * statement) — so the field keeps its third state rather than quietly losing the way back to absent.
     */
    @Test
    fun anExplicitNullDefaultIsNotADefault() {
        assertEquals(false, twoState(mapOf(SCH.default to null)))
    }
}
