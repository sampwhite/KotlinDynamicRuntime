package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.DesignOrigin
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchLayout
import com.dynamicruntime.common.schema.SchLayoutField
import com.dynamicruntime.common.schema.SchLayoutMode
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Design View's pure pieces (issue #972): the field plan both presentations follow, the walk from a field's data path
 * to the named type that declares it, the address said for people, and reading the backend's block. The rendering --
 * markers, ghosts, the inspector -- is checked in a browser.
 */
class DesignViewTest {
    private val types: Map<String, SchType> = parseSchemaTypes(
        mapOf(
            "client.demo.Contact" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf("email" to mapOf(SCH.type to SCT.string)),
            ),
            "client.demo.Request" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "title" to mapOf(SCH.type to SCT.string),
                    "venue" to mapOf(SCH.type to SCT.string),
                    "rainPlan" to mapOf(SCH.type to SCT.string),
                    "adminNote" to mapOf(SCH.type to SCT.string, SCH.visibleWhen to "kdr:hasAdminLevel"),
                    "total" to mapOf(SCH.type to SCT.number, SCH.derived to true),
                    "contact" to mapOf(SCH.dRef to "#/\$defs/client.demo.Contact"),
                    "guests" to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.dRef to "#/\$defs/client.demo.Contact")),
                    "extra" to mapOf(
                        SCH.type to SCT.kObject,
                        SCH.properties to mapOf("note" to mapOf(SCH.type to SCT.string)),
                    ),
                ),
                // `rainPlan` is asked only for an outdoor venue.
                SCH.kIf to mapOf(
                    SCH.required to listOf("venue"),
                    SCH.properties to mapOf("venue" to mapOf(SCH.const to "outdoors")),
                ),
                SCH.kThen to mapOf(SCH.required to listOf("rainPlan")),
                SCH.kElse to mapOf(SCH.not to mapOf(SCH.required to listOf("rainPlan"))),
            ),
        ),
    )
    private val request = types.getValue("client.demo.Request")

    private fun opts(admin: Boolean, layout: SchLayout? = null) = FormOpts(
        friendly = true,
        gateAllows = { admin },
        fieldLayouts = layout?.let { mapOf("client.demo.Request" to it) } ?: emptyMap(),
    )

    private fun plan(values: Map<String, Any?>, editable: Boolean, o: FormOpts): List<PlannedField> {
        val condition = request.condition
        val forbidden = condition?.forbiddenWhen(condition.holds(values)) ?: emptySet()
        return formFieldPlan(request, values, "", editable, forbidden, o)
    }

    private fun hiddenOf(p: List<PlannedField>): Map<String, FieldHidden> =
        p.mapNotNull { f -> f.hidden?.let { f.name to it } }.toMap()

    @Test
    fun eachHiddenFieldSaysWhyAndTheRestAreDrawnInOrder() {
        val p = plan(emptyMap(), editable = true, opts(admin = false))
        assertEquals(listOf("title", "venue", "contact", "guests", "extra"), p.filter { it.hidden == null }.map { it.name })
        assertEquals(
            mapOf("rainPlan" to FieldHidden.forbidden, "adminNote" to FieldHidden.notForViewer, "total" to FieldHidden.derived),
            hiddenOf(p),
        )
    }

    /** One decision, two presentations: answering "outdoors" and being an administrator each bring a ghost back. */
    @Test
    fun theSameRuleDecidesBothPresentations() {
        val p = plan(mapOf("venue" to "outdoors"), editable = true, opts(admin = true))
        assertEquals(mapOf("total" to FieldHidden.derived), hiddenOf(p))
        // A read-only form shows a visibility-gated field whatever the viewer, as it always has.
        assertNull(hiddenOf(plan(emptyMap(), editable = false, opts(admin = false)))["adminNote"])
    }

    @Test
    fun anAuthoritativeLayoutLeavesTheRestOutAtTheEnd() {
        val layout = SchLayout(
            fragmentFileId = null, label = null,
            fields = listOf(SchLayoutField("venue", null, null, null), SchLayoutField("title", null, null, null)),
            mode = SchLayoutMode.authoritative,
        )
        val p = plan(emptyMap(), editable = true, opts(admin = true, layout))
        assertEquals(listOf("venue", "title"), p.filter { it.hidden == null }.map { it.name })
        // Every field the layout does not list follows, as left out by it -- whatever else might have hidden it.
        assertEquals(
            listOf("rainPlan", "adminNote", "total", "contact", "guests", "extra"),
            p.drop(2).map { it.name },
        )
        assertEquals(setOf(FieldHidden.notInLayout), p.drop(2).map { it.hidden }.toSet())
    }

    @Test
    fun aFieldBelongsToTheNamedTypeThatDeclaresIt() {
        fun owner(path: String) = fieldOwner("client.demo.Request", request, path).let { it.typeName to it.schemaPath }
        assertEquals("client.demo.Request" to listOf(SCH.properties, "title"), owner("title"))
        // Into a referenced type: that type now owns the field, and the path starts over inside it.
        assertEquals("client.demo.Contact" to listOf(SCH.properties, "email"), owner("contact.email"))
        // Through an array of a referenced type, an element index ignored.
        assertEquals("client.demo.Contact" to listOf(SCH.properties, "email"), owner("guests[0].email"))
        // An inline object stays with its parent.
        assertEquals(
            "client.demo.Request" to listOf(SCH.properties, "extra", SCH.properties, "note"),
            owner("extra.note"),
        )
    }

    @Test
    fun anAddressReadsAsNamesAndExtendsIntoItsBody() {
        val trait = DesignAddress(CCT.traitDef, "eventRequest", CCT.dataSchema, DesignOrigin.stored.name, "designDemo", true)
        val field = trait.below(listOf(SCH.properties, "venue"))
        assertEquals("dataSchema.properties.venue", field.path)
        assertEquals("trait eventRequest › venue", addressLine(field))
        assertEquals("This client's stored configuration (designDemo)", originText(field))
        assertEquals("type kdr.Thing", addressLine(DesignAddress(CCT.schemaDef, "kdr.Thing", null, "global", null, false)))
    }

    @Test
    fun theAuthoredEntryIsReadAtAnAddressAndItsLayoutEntryBesideIt() {
        val body = mapOf(
            SCH.properties to mapOf("venue" to mapOf(SCH.type to SCT.string)),
            SCH.layout to mapOf("schemaFields" to listOf(mapOf("field" to "venue", "label" to "Venue"))),
        )
        val entry = mapOf(CCT.traitId to "eventRequest", CCT.dataSchema to body)
        assertEquals(mapOf(SCH.type to SCT.string), subtreeAt(entry, "dataSchema.properties.venue"))
        assertNull(subtreeAt(entry, "dataSchema.properties.missing"))
        assertEquals("Venue", layoutEntryIn(body, "venue")?.get("label"))
        assertNull(layoutEntryIn(body, "title"))
    }

    @Test
    fun theBackendsBlockIsReadAndAnOrdinaryViewHasNone() {
        assertNull(parseWfDesign(null))
        val design = parseWfDesign(
            mapOf(
                DSV.workflow to mapOf(DSV.slot to CCT.workflowDef, DSV.key to "requestEvent", DSV.origin to "stored", DSV.editable to true),
                DSV.types to mapOf(
                    "client.demo.Request" to mapOf(DSV.slot to CCT.traitDef, DSV.key to "eventRequest", DSV.path to CCT.dataSchema),
                    "broken" to mapOf(DSV.key to "noSlot"),
                ),
            ),
        )!!
        assertEquals("requestEvent", design.workflow?.key)
        assertEquals(true, design.workflow?.editable)
        assertEquals(setOf("client.demo.Request"), design.types.keys)
        // An origin left out reads as global, the cautious answer: nothing claims it is editable.
        assertEquals(DesignOrigin.global.name, design.types.getValue("client.demo.Request").origin)
    }
}
