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
    fun aReadOnlyValueOnAFormPeopleFillInReadsAsWords() {
        // Issue #1438: a yes/no answer as Yes or No, a choice by its label, each of a list too; a value the schema does
        // not list, and anything else, as it is.
        val choice = fieldType(string(mapOf(SCH.options to opts)))
        assertEquals("Yes", friendlyValueText(fieldType(mapOf(SCH.type to SCT.boolean)), true))
        assertEquals("No", friendlyValueText(fieldType(mapOf(SCH.type to SCT.boolean)), false))
        assertEquals("A", friendlyValueText(choice, "a"))
        assertEquals("c", friendlyValueText(choice, "c"))
        assertEquals("A, B", friendlyValueText(fieldType(array(string(mapOf(SCH.options to opts)))), listOf("a", "b")))
        assertEquals("12", friendlyValueText(fieldType(mapOf(SCH.type to SCT.integer)), 12))
        assertEquals("", friendlyValueText(choice, null))
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
    fun longTextIsTheSchemasWordOnPlainText() {
        // Issue #1550: a string the schema marks `g-longText` is a text area on every form, the endpoint catalog's too,
        // since it reads the schema and not a layout. Unmarked text is one line, and a read-only field is its value.
        val long = string(mapOf(SCH.longText to true))
        assertEquals(ControlKind.LongText, kindOf(long))
        assertEquals(ControlKind.Text, kindOf(string()))
        assertEquals(ControlKind.ReadOnly, kindOf(long, editable = false))
    }

    @Test
    fun aTextAreaFitsItsTextBetweenItsBounds() {
        assertEquals(3, fittedRows("", 3, 12))
        assertEquals(5, fittedRows("a\nb\nc\nd\ne", 3, 12))
        assertEquals(12, fittedRows("x\n".repeat(40), 3, 12))
        // A paragraph typed without a break counts the lines it wraps to: 200 characters at 80 a line is three.
        assertEquals(4, fittedRows("a".repeat(200) + "\nb", 3, 12))
        // A layout's lines above the fit's cap still start that tall.
        assertEquals(20, fittedRows("", 20, maxOf(20, 12)))
    }

    @Test
    fun optionsWinOverTheBaseType() {
        // The ordering that matters: a field carrying a choice list is a choice widget, not the text box its base
        // type would get. A list is allowed only on a plain string or an untyped field -- the parser refuses one on
        // a boolean or an integer (issue #815), where it would have rejected every correctly typed value.
        assertEquals(ControlKind.Choice, kindOf(mapOf(SCH.type to SCT.string, SCH.options to opts), required = true))
        assertEquals(ControlKind.Choice, kindOf(mapOf(SCH.options to opts)))
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
