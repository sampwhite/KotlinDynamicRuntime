package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.validate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A map on a form (issue #1055): an object of free keys and typed values is drawn as the one control an object with
 * no declared fields gets, a JSON editor, and the page validates it with the kernel's own validator -- which now
 * reports a failure *inside* an entry, a place the control has no slot for. [FieldErrors.messagesWithin] is what
 * shows it with the field, led by where in the map it is.
 */
class MapFieldErrorsTest {
    private val order = parseSchemaTypes(
        mapOf(
            "f.Order" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "title" to mapOf(SCH.type to SCT.string),
                    "scores" to mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to mapOf(SCH.type to SCT.integer)),
                    "copy" to mapOf(
                        SCH.type to SCT.kObject,
                        SCH.additionalProperties to mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to mapOf(SCH.type to SCT.string)),
                    ),
                ),
            ),
        ),
    ).getValue("f.Order")

    @Test
    fun aMapIsNamedForItsValues() {
        // "(object)" said nothing of what an entry holds, which is all a map declares.
        assertEquals("map of integer", typeWord(order.properties.getValue("scores").valueType))
        assertEquals("map of map of string", typeWord(order.properties.getValue("copy").valueType))
        assertEquals("string", typeWord(order.properties.getValue("title").valueType))
        // A map whose values are the map itself is named a few levels down and no further.
        val folder = parseSchemaTypes(
            mapOf("f.Folder" to mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to mapOf(SCH.dRef to "#/\$defs/f.Folder"))),
        ).getValue("f.Folder")
        assertEquals("map of map of map of map", typeWord(folder))
    }

    @Test
    fun anOutlineExpandsWhatAMapsEntriesHold() {
        val types = parseSchemaTypes(
            mapOf(
                "f.Line" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("text" to mapOf(SCH.type to SCT.string))),
                "f.Cart" to mapOf(
                    SCH.type to SCT.kObject,
                    SCH.properties to mapOf(
                        "lines" to mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to mapOf(SCH.dRef to "#/\$defs/f.Line")),
                        "list" to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.dRef to "#/\$defs/f.Line")),
                        // Declared fields and a value type: its own fields are what the outline shows.
                        "tally" to mapOf(
                            SCH.type to SCT.kObject,
                            SCH.properties to mapOf("kind" to mapOf(SCH.type to SCT.string)),
                            SCH.additionalProperties to mapOf(SCH.type to SCT.integer),
                        ),
                    ),
                ),
            ),
        )
        val cart = types.getValue("f.Cart")
        fun element(name: String) = outlineElement(cart.properties.getValue(name).valueType)
        assertEquals("f.Line", element("lines")?.name)
        assertEquals("f.Line", element("list")?.name)
        assertEquals(null, element("tally"))
        assertEquals(null, outlineElement(cart.properties.getValue("lines").valueType.additionalValueType!!))
        assertEquals("map of object", typeWord(cart.properties.getValue("lines").valueType))
    }

    @Test
    fun aMapIsDrawnAsOneJsonControl() {
        // No declared fields of its own, so the form draws it as it draws any such object.
        assertEquals(ControlKind.JsonMap, controlKind(order.properties.getValue("scores").valueType, required = false, editable = true))
    }

    @Test
    fun aFailureInsideAnEntryIsShownWithTheFieldLedByItsKey() {
        // The browser runs the validator the server runs, so it finds the same failures at the same paths.
        val failures = validate(
            order,
            mapOf("title" to "T", "scores" to mapOf("ann" to 5, "bo" to "many"), "copy" to mapOf("home" to mapOf("title" to 7))),
        )
        assertEquals(setOf("scores.bo", "copy.home.title"), failures.map { it.path }.toSet())
        val errors = FieldErrors(failures, noteEdit = {})
        // Nothing is reported at the field itself, which is all the control used to be shown.
        assertEquals(emptyList(), errors.messagesAt("scores"))
        val shown = errors.messagesWithin("scores").single()
        assertEquals(SchFailCode.badValue, shown.code)
        assertEquals(true, shown.message.startsWith("bo: "))
        assertEquals(true, errors.messagesWithin("copy").single().message.startsWith("home.title: "))
        // A field with nothing wrong inside it shows nothing, and a failure at the field itself is shown as it is.
        assertEquals(emptyList(), errors.messagesWithin("title"))
        val wrongKind = validate(order, mapOf("scores" to "none"))
        assertEquals(listOf("scores"), FieldErrors(wrongKind, noteEdit = {}).messagesWithin("scores").map { it.path })
        assertEquals(wrongKind.single().message, FieldErrors(wrongKind, noteEdit = {}).messagesWithin("scores").single().message)
    }
}
