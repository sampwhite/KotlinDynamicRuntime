package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic coverage (issue #161) for [checkInput], the shared coerce-and-validate the endpoint catalog and
 * the new-form page hold their input to (issue #408): a clean check hands back a payload to send, a failing
 * one withholds it so nothing invalid reaches the wire.
 */
class SchemaRunTest {

    /** A one-field object type: `title`, string, required so absence is a failure. */
    private fun titleType(): SchType {
        val schema = mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf("title" to mapOf(SCH.type to SCT.string, SCH.description to "A title")),
            SCH.required to listOf("title"),
        )
        return parseSchemaTypes(mapOf("t.Form" to schema)).getValue("t.Form")
    }

    @Test
    fun validInputProducesAPayload() {
        val check = checkInput(titleType(), mapOf("title" to "Expenses"))
        assertTrue(check.isValid)
        assertEquals(mapOf("title" to "Expenses"), check.payload)
    }

    @Test
    fun invalidInputWithholdsThePayload() {
        val check = checkInput(titleType(), emptyMap())
        assertTrue(check.failures.isNotEmpty())
        assertNull(check.payload)
    }
}
