package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-logic coverage (issue #781) for [controlKind] — which input control a field draws from its schema. This
 * is the form's single most consequential presentation rule, and it was previously exercised only by driving a
 * browser; here it is schema in, [ControlKind] out. The types are built by parsing real schema through the
 * kernel's own [parseSchemaTypes], so what is under test is the schema a backend actually writes.
 */
class ControlKindTest {

    /** The value-type of field `f`, built from [fieldSchema], with the parent marking it [required] or not. */
    private fun fieldType(fieldSchema: Map<String, Any?>, required: Boolean = false): SchType {
        val schema = mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf("f" to (mapOf<String, Any?>(SCH.description to "A field") + fieldSchema)),
            SCH.required to if (required) listOf("f") else emptyList(),
        )
        return parseSchemaTypes(mapOf("t.T" to schema)).getValue("t.T").properties.getValue("f").valueType
    }

    private fun kindOf(fieldSchema: Map<String, Any?>, required: Boolean = false, editable: Boolean = true) =
        controlKind(fieldType(fieldSchema, required), required, editable)

    private val opts = listOf(mapOf(SCH.value to "a", SCH.label to "A"), mapOf(SCH.value to "b", SCH.label to "B"))
    private fun string(extra: Map<String, Any?> = emptyMap()) = mapOf<String, Any?>(SCH.type to SCT.string) + extra
    private fun array(items: Map<String, Any?>) = mapOf<String, Any?>(SCH.type to SCT.array, SCH.items to items)

    @Test
    fun notEditableIsReadOnlyWhateverTheType() {
        // A field that would otherwise be a Choice, a checkbox, or a date is ReadOnly when not editable --
        // editability is checked before the type, so the read-only view never draws a live control.
        assertEquals(ControlKind.ReadOnly, kindOf(string(mapOf(SCH.options to opts)), editable = false))
        assertEquals(ControlKind.ReadOnly, kindOf(mapOf(SCH.type to SCT.boolean), required = true, editable = false))
        assertEquals(ControlKind.ReadOnly, kindOf(string(mapOf(SCH.format to SFMT.date)), editable = false))
    }

    @Test
    fun multiSelectFromAnArrayOfChoices() {
        assertEquals(ControlKind.MultiSelect, kindOf(array(string(mapOf(SCH.options to opts)))))
        // An open element type accepts a value not offered -> tags mode.
        assertEquals(
            ControlKind.MultiSelectOpen,
            kindOf(array(string(mapOf(SCH.options to opts, SCH.openOptions to true)))),
        )
        // An array without item options is not a multi-select (it is a growing column elsewhere) -> falls to Text.
        assertEquals(ControlKind.Text, kindOf(array(string())))
    }

    @Test
    fun singleChoiceClosedAndOpen() {
        assertEquals(ControlKind.Choice, kindOf(string(mapOf(SCH.options to opts))))
        assertEquals(ControlKind.OpenChoice, kindOf(string(mapOf(SCH.options to opts, SCH.openOptions to true))))
    }

    @Test
    fun booleanIsCheckboxWhenTwoStateElseTristateSelect() {
        // Required (or with a default) -> two reachable states -> checkbox; a plain optional boolean keeps a
        // reachable "absent" third state, which a checkbox cannot express -> the tri-state Select.
        assertEquals(ControlKind.Checkbox, kindOf(mapOf(SCH.type to SCT.boolean), required = true))
        assertEquals(ControlKind.Checkbox, kindOf(mapOf(SCH.type to SCT.boolean, SCH.default to false)))
        assertEquals(ControlKind.TristateBoolean, kindOf(mapOf(SCH.type to SCT.boolean)))
    }

    @Test
    fun dateFormatsFileAndJsonMap() {
        assertEquals(ControlKind.Date, kindOf(string(mapOf(SCH.format to SFMT.date))))
        assertEquals(ControlKind.DateTime, kindOf(string(mapOf(SCH.format to SFMT.dateTime))))
        assertEquals(ControlKind.File, kindOf(string(mapOf(SCH.format to SFMT.binary))))
        // A property-less object is a free-form map editor, not a text box.
        assertEquals(ControlKind.JsonMap, kindOf(mapOf(SCH.type to SCT.kObject)))
    }

    @Test
    fun plainScalarsAreText() {
        assertEquals(ControlKind.Text, kindOf(string()))
        assertEquals(ControlKind.Text, kindOf(mapOf(SCH.type to SCT.integer)))
        assertEquals(ControlKind.Text, kindOf(mapOf(SCH.type to SCT.number)))
    }

    @Test
    fun optionsWinOverTheBaseType() {
        // The ordering that matters: a field carrying a choice list is a choice widget regardless of its base
        // type -- so a boolean or an integer with `g-options` is a Choice, not a checkbox or a text box.
        assertEquals(ControlKind.Choice, kindOf(mapOf(SCH.type to SCT.boolean, SCH.options to opts), required = true))
        assertEquals(ControlKind.Choice, kindOf(mapOf(SCH.type to SCT.integer, SCH.options to opts)))
    }

    // --- typeWord: the read-only outline's "type in words", which shares controlKind's choice reading ---

    private fun wordOf(fieldSchema: Map<String, Any?>) = typeWord(fieldType(fieldSchema))

    @Test
    fun typeWordDistinguishesOpenFromClosedChoices() {
        // The distinction the outline must not lose: an open list is "not the whole of what is allowed", so its
        // word says so -- the same reading controlKind turns into OpenChoice vs Choice.
        assertEquals("choice", wordOf(string(mapOf(SCH.options to opts))))
        assertEquals("open choice", wordOf(string(mapOf(SCH.options to opts, SCH.openOptions to true))))
        assertEquals("choices", wordOf(array(string(mapOf(SCH.options to opts)))))
        assertEquals("open choices", wordOf(array(string(mapOf(SCH.options to opts, SCH.openOptions to true)))))
        assertEquals("list", wordOf(array(string())))
        assertEquals("file", wordOf(string(mapOf(SCH.format to SFMT.binary))))
        assertEquals(SFMT.date, wordOf(string(mapOf(SCH.format to SFMT.date))))
        assertEquals("boolean", wordOf(mapOf(SCH.type to SCT.boolean)))
        assertEquals("string", wordOf(string()))
    }
}
