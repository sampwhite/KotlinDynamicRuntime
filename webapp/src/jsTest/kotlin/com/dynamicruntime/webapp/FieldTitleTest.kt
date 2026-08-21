package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-logic coverage for where a friendly field label's `title` comes from (issue #408).
 *
 * The case worth protecting is the last: a `$ref` field's `valueType` is the **shared** target instance, so a
 * title read from there would name every field referencing that type alike. `SchProperty.title` keeps a
 * field's label its own.
 */
class FieldTitleTest {

    /** Parses one document and returns the named type. */
    private fun typeOf(defs: Map<String, Any?>, name: String): SchType = parseSchemaTypes(defs).getValue(name)

    /** An inline field's title lands on the property, so it labels that field. */
    @Test
    fun anInlineFieldKeepsItsOwnTitle() {
        val defs = mapOf(
            "t.Form" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "perItemAmount" to mapOf(SCH.type to SCT.number, SCH.title to "Amount per item"),
                ),
            ),
        )
        assertEquals("Amount per item", typeOf(defs, "t.Form").properties.getValue("perItemAmount").title)
    }

    /** A field that declares no title has none; the form humanizes its key instead. */
    @Test
    fun anUntitledFieldHasNoTitle() {
        val defs = mapOf(
            "t.Form" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf("perItemAmount" to mapOf(SCH.type to SCT.number)),
            ),
        )
        assertNull(typeOf(defs, "t.Form").properties.getValue("perItemAmount").title)
        assertEquals("Per item amount", humanizeFieldName("perItemAmount"))
    }

    /**
     * Two `$ref` fields over one titled type keep their own identities: neither inherits "Address", and a
     * title written beside a `$ref` belongs to that field alone.
     */
    @Test
    fun refFieldsDoNotInheritTheTargetTypeTitle() {
        val defs = mapOf(
            "t.Address" to mapOf(
                SCH.type to SCT.kObject,
                SCH.title to "Address",
                SCH.properties to mapOf("postcode" to mapOf(SCH.type to SCT.string)),
            ),
            "t.Contact" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "homeAddress" to mapOf(SCH.dRef to "t.Address"),
                    // The same target, this one labeled for itself.
                    "workAddress" to mapOf(SCH.dRef to "t.Address", SCH.title to "Work address"),
                ),
            ),
        )
        val contact = typeOf(defs, "t.Contact")
        // The shared target does carry the title -- it is the type's own, and the union's branch labels read it.
        assertEquals("Address", contact.properties.getValue("homeAddress").valueType.title)
        // ...but neither field takes it as its label.
        assertNull(contact.properties.getValue("homeAddress").title)
        assertEquals("Work address", contact.properties.getValue("workAddress").title)
        // So the two rows stay distinguishable, which is the whole point.
        assertEquals("Home address", humanizeFieldName("homeAddress"))
    }
}
