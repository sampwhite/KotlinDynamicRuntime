package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.DesignOrigin
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchLayout
import com.dynamicruntime.common.schema.SchLayoutField
import com.dynamicruntime.common.schema.SchLayoutMode
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.util.toJsonListOfMaps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
                    "contact" to mapOf(SCH.dRef to $$"#/$defs/client.demo.Contact"),
                    "guests" to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.dRef to $$"#/$defs/client.demo.Contact")),
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
        val trait = DesignAddress(CCT.traitDef, "eventRequest", CCT.dataSchema, DesignOrigin.stored.name, "designDemo")
        val field = trait.below(listOf(SCH.properties, "venue"))
        assertEquals("dataSchema.properties.venue", field.path)
        assertEquals("trait eventRequest › venue", addressLine(field))
        assertEquals("type kdr.Thing", addressLine(DesignAddress(CCT.schemaDef, "kdr.Thing", null, "global", null)))
    }

    @Test
    fun provenanceIsOneSentenceSharedOrTheClientsOwnWithAnyAlteration() {
        fun at(origin: DesignOrigin, config: String?, alteredBy: DesignLayer? = null) =
            DesignAddress(CCT.schemaDef, "x.Thing", null, origin.name, config, alteredBy)
        assertEquals("This client's own, declared in its stored configuration (designDemo).", provenanceText(at(DesignOrigin.stored, "designDemo")))
        assertEquals("This client's own, declared in source (acmeClient).", provenanceText(at(DesignOrigin.source, "acmeClient")))
        assertEquals("Shared by every client, from coreTraits.", provenanceText(at(DesignOrigin.global, "coreTraits")))
        assertEquals("Shared by every client, from the platform.", provenanceText(at(DesignOrigin.global, null)))
        assertEquals(
            "Shared by every client, from sampleTraits; altered for this client in source (acmeClient).",
            provenanceText(at(DesignOrigin.global, "sampleTraits", DesignLayer(DesignOrigin.source.name, "acmeClient"))),
        )
        // The alteration rides below the address into a field, so a field reads the same as its type.
        val field = at(DesignOrigin.global, "coreTraits", DesignLayer(DesignOrigin.stored.name, "copy")).below(listOf("name"))
        assertEquals("Shared by every client, from coreTraits; altered for this client in its stored configuration (copy).", provenanceText(field))
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
                DSV.workflow to mapOf(DSV.slot to CCT.workflowDef, DSV.key to "requestEvent", DSV.origin to "stored"),
                DSV.types to mapOf(
                    "client.demo.Request" to mapOf(DSV.slot to CCT.traitDef, DSV.key to "eventRequest", DSV.path to CCT.dataSchema),
                    "kdr.core.NameData" to mapOf(
                        DSV.slot to CCT.traitDef, DSV.key to "kdr:name", DSV.origin to "global", DSV.config to "coreTraits",
                        DSV.alteredBy to mapOf(DSV.origin to "stored", DSV.config to "copy"),
                    ),
                    "broken" to mapOf(DSV.key to "noSlot"),
                ),
            ),
        )!!
        assertEquals("requestEvent", design.workflow?.key)
        assertEquals(DesignOrigin.stored.name, design.workflow?.origin)
        assertEquals(setOf("client.demo.Request", "kdr.core.NameData"), design.types.keys)
        // An origin left out reads as global, the cautious answer: nothing claims it is the client's own.
        assertEquals(DesignOrigin.global.name, design.types.getValue("client.demo.Request").origin)
        assertNull(design.types.getValue("client.demo.Request").alteredBy)
        val altered = design.types.getValue("kdr.core.NameData").alteredBy
        assertEquals("copy", altered?.config)
        assertEquals(DesignOrigin.stored.name, altered?.origin)
    }

    // --- editing a field's copy for the workflow (issue #984) ---

    @Test
    fun theBlocksEditFactsAreRead() {
        val design = parseWfDesign(
            mapOf(
                DSV.canEdit to true,
                DSV.basedOn to "abc123",
                DSV.layoutEdits to mapOf(
                    "client.demo.Request" to mapOf(
                        "title" to mapOf(
                            DSV.entry to mapOf("field" to "title", "label" to "Name it"),
                            DSV.inherited to mapOf("field" to "title", "label" to "Title"),
                            DSV.inheritedChanged to true,
                        ),
                    ),
                ),
            ),
        )!!
        assertEquals(true, design.canEdit)
        assertEquals("abc123", design.basedOn)
        val edit = design.layoutEdit("client.demo.Request", "title")!!
        assertEquals("Name it", edit.entry["label"])
        assertEquals("Title", edit.inherited?.get("label"))
        assertEquals(true, edit.inheritedChanged)
        assertNull(design.layoutEdit("client.demo.Request", "venue"))
        // An ordinary block says nothing about editing, and offers none.
        assertEquals(false, parseWfDesign(emptyMap<String, Any?>())!!.canEdit)
    }

    @Test
    fun aSavedEntryTakesTheFormsCopyAndKeepsWhatTheFormDoesNotOffer() {
        val start = mapOf("field" to "title", "label" to "Title", "hint" to "Short", "defaultMode" to "offer")
        val out = copyEntryFrom(start, "title", mapOf("label" to " Name it ", "description" to "What people call it", "hint" to ""))
        assertEquals(
            mapOf("field" to "title", "label" to "Name it", "defaultMode" to "offer", "description" to "What people call it"),
            out,
        )
        // Starting from nothing, the field is still named.
        assertEquals(mapOf("field" to "venue", "label" to "Where"), copyEntryFrom(emptyMap(), "venue", mapOf("label" to "Where")))
    }

    @Test
    fun anOverridesPathQuotesTheDottedTypeName() {
        assertEquals(
            "definition.types[\"client.demo.Request\"].g-layout.schemaFields[title]",
            overridePath("client.demo.Request", "title"),
        )
    }

    // --- the shared editor (issue #1029) ---

    private val sharedRead = mapOf(
        DSV.usedBy to listOf(
            mapOf(DSV.workflowId to "requestEvent", DSV.label to "Request an event"),
            mapOf(DSV.workflowId to "reviewEvent", DSV.label to "Event request"),
        ),
        DSV.variantFields to mapOf("title" to listOf("requestEvent")),
        DSV.canEditShared to true,
        DSV.sharedBasedOn to "abc123",
    )

    @Test
    fun sharedFactsAreReadAndAWorkflowsReadHasNone() {
        val facts = parseSharedFacts(sharedRead)!!
        assertEquals(true, facts.canEdit)
        assertEquals("abc123", facts.basedOn)
        assertEquals("Used by 2 workflows: Request an event, Event request.", usedByText(facts.usedBy))
        assertNull(parseSharedFacts(mapOf(DSV.entry to emptyMap<String, Any?>())))
        assertEquals("Not used by any workflow yet.", usedByText(emptyList()))
    }

    @Test
    fun aWorkflowWithItsOwnCopyIsNamedForTheFieldItOverrides() {
        val facts = parseSharedFacts(sharedRead)!!
        assertEquals(
            "Request an event keeps its own copy of this field, so a shared change to its copy will not show there.",
            variantNote(facts, "title"),
        )
        assertNull(variantNote(facts, "venue"))
    }

    @Test
    fun choicesBecomeRowsAndRowsTheChoicesSent() {
        val schema = mapOf(SCH.options to listOf(mapOf(SCH.label to "At the office", SCH.value to "office")))
        val rows = choiceRowsOf(schema)!!
        assertEquals(listOf("office"), rows.map { it.value })
        assertNull(choiceRowsOf(mapOf(SCH.type to "string")))
        val sent = sharedOptionsPayload(rows + ChoiceRow(" park ", "", isNew = true) + ChoiceRow("", "blank", isNew = true))
        assertEquals(
            listOf(mapOf(SCH.value to "office", SCH.label to "At the office"), mapOf(SCH.value to "park", SCH.label to "park")),
            sent,
        )
    }

    @Test
    fun copyIsSentOnlyWhenItChanged() {
        val start = mapOf(SL.label to "Venue", SL.hint to "Where.")
        assertEquals(false, copyChanged(start, mapOf(SL.label to "Venue", SL.description to "", SL.hint to "Where. ")))
        assertEquals(true, copyChanged(start, mapOf(SL.label to "Place", SL.description to "", SL.hint to "Where.")))
        // A field with no entry and nothing typed has nothing to send.
        assertEquals(false, copyChanged(emptyMap(), mapOf(SL.label to "", SL.description to "", SL.hint to "")))
    }

    // --- what a blank copy input stands for (issue #1039) ---

    private val fallbackTypes = parseSchemaTypes(
        mapOf(
            "client.demo.Fallbacks" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "phone" to mapOf(SCH.type to SCT.string, SCH.title to "Phone number", SCH.description to "A number to call."),
                    "catering" to mapOf(SCH.type to SCT.boolean),
                    "attendees" to mapOf(SCH.type to SCT.integer, SCH.minimum to 1, SCH.maximum to 200),
                ),
            ),
        ),
    ).getValue("client.demo.Fallbacks")

    private fun fallback(key: String, field: String) = copyFallback(key, field, fallbackTypes.properties.getValue(field))

    @Test
    fun aBlankLabelIsTheFieldsTitleOrItsNameMadeReadable() {
        assertEquals("Phone number", fallback(SL.label, "phone").text)
        assertEquals("Blank: forms show the field's own title.", fallback(SL.label, "phone").note)
        assertEquals("Catering", fallback(SL.label, "catering").text)
        assertEquals("Blank: forms show a label made from the field's name.", fallback(SL.label, "catering").note)
    }

    @Test
    fun aBlankDescriptionIsTheFieldsOwnOrNone() {
        assertEquals("A number to call.", fallback(SL.description, "phone").text)
        assertEquals("Blank: forms show the field's own description.", fallback(SL.description, "phone").note)
        assertNull(fallback(SL.description, "catering").text)
        assertEquals("Blank: forms show no description.", fallback(SL.description, "catering").note)
    }

    @Test
    fun aBlankHintIsTheRangeTheBoundsGiveOrNone() {
        assertEquals("range: 1 to 200", fallback(SL.hint, "attendees").text)
        assertEquals("Blank: forms show the field's range.", fallback(SL.hint, "attendees").note)
        assertNull(fallback(SL.hint, "phone").text)
        assertEquals("Blank: forms show no hint.", fallback(SL.hint, "phone").note)
    }

    @Test
    fun aFieldThatCannotTakeCopyIsReadWithItsReason() {
        val facts = parseSharedFacts(sharedRead + (DSV.sharedCopyRefusals to mapOf("phone" to "Not in its list.")))!!
        assertEquals(mapOf("phone" to "Not in its list."), facts.copyRefusals)
        assertEquals(emptyMap(), parseSharedFacts(sharedRead)!!.copyRefusals)
    }

    // --- removing a choice (issue #1040) ---

    @Test
    fun aRemovedChoiceIsLeftOutAndNamed() {
        val start = listOf(ChoiceRow("office", "At the office", false), ChoiceRow("hotel", "A hotel", false), ChoiceRow("park", "A park", false))
        val rows = listOf(start[0], ChoiceRow("venue", "A venue", isNew = true))
        assertEquals(listOf("hotel", "park"), removedChoiceValues(start, rows))
        assertEquals(listOf("office", "venue"), sharedOptionsPayload(rows).map { it[SCH.value] })
        assertEquals(emptyList(), removedChoiceValues(start, start))
        assertEquals(emptyList(), removedChoiceValues(null, rows))
    }

    @Test
    fun aRemovalIsSaidBeforeTheSave() {
        assertEquals("Removing hotel", removingPhrase(listOf("hotel")))
        assertEquals("Removing office, hotel and park", removingPhrase(listOf("office", "hotel", "park")))
        assertEquals(
            "Removing hotel: saving first checks whether the client's stored forms hold it.",
            removingNote(listOf("hotel")),
        )
        assertNull(removingNote(emptyList()))
    }

    @Test
    fun saveAnywayAcknowledgesTheImpactAndAPlainSaveDoesNot() {
        val options = listOf(mapOf<String, Any?>(SCH.value to "office", SCH.label to "At the office"))
        val plain = sharedFieldBody("client.demo.Request", "venue", null, options, "abc123", acknowledgeImpact = false)
        assertEquals(setOf(DSV.typeName, DSV.field, DSV.options, DSV.sharedBasedOn), plain.keys)
        val anyway = sharedFieldBody("client.demo.Request", "venue", null, options, "abc123", acknowledgeImpact = true)
        assertEquals(true, anyway[IMP.acknowledgeImpact])
    }

    // --- a workflow form's own requirements (issue #1048) ---

    private val requirementTypes = parseSchemaTypes(
        mapOf(
            "client.demo.Event" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "venue" to mapOf(
                        SCH.type to SCT.string,
                        SCH.options to listOf(
                            mapOf(SCH.value to "office", SCH.label to "At the office"),
                            mapOf(SCH.value to "hotel", SCH.label to "A hotel"),
                            mapOf(SCH.value to "park", SCH.label to "A park"),
                        ),
                    ),
                    "note" to mapOf(SCH.type to SCT.string, SCH.visibleWhen to "kdr:hasAdminLevel"),
                    "total" to mapOf(SCH.type to SCT.number, SCH.derived to true),
                ),
            ),
        ),
    ).getValue("client.demo.Event")
    private val venueType = requirementTypes.properties.getValue("venue").valueType

    @Test
    fun choiceRowsStartFromTheEntryOrOfferEverything() {
        val all = formChoiceRowsOf(venueType, emptyMap())!!
        assertEquals(listOf("office", "hotel", "park"), all.map { it.value })
        // Every label filled in from the schema's, ready to change: a customized list owns its copy.
        assertTrue(all.all { it.offered && it.label == it.schemaLabel })
        // Restated: the offered ones first, in the entry's order, with its labels; the rest after, not offered.
        val restated = formChoiceRowsOf(
            venueType,
            mapOf(SL.choices to listOf(mapOf(SL.value to "park", SL.label to "Outdoors"), mapOf(SL.value to "office"))),
        )!!
        assertEquals(listOf("park" to true, "office" to true, "hotel" to false), restated.map { it.value to it.offered })
        assertEquals("Outdoors", restated.first().label)
        // A restated choice with no label of its own, and one not offered, show the schema's.
        assertEquals("At the office", restated[1].label)
        assertEquals("A hotel", restated.last().label)
        assertNull(formChoiceRowsOf(requirementTypes.properties.getValue("note").valueType, emptyMap()))
    }

    @Test
    fun theSavedEntryKeepsTheCopyAndWritesOnlyWhatTheFormAdds() {
        val start = mapOf(SL.field to "venue", SL.label to "Venue", SL.required to true)
        val copy = mapOf(SL.label to "Venue", SL.description to "", SL.hint to "")
        val everything = formChoiceRowsOf(venueType, emptyMap())!!
        // The schema's list as it is: no `choices`, so the form follows the schema's list. Unrequired: no key.
        assertEquals(mapOf(SL.field to "venue", SL.label to "Venue"), formEntryFrom(start, "venue", copy, required = false, rows = everything))
        // Customized -- one choice dropped, one relabeled -- the list is written in full, every label with it.
        val fewer = everything.map { if (it.value == "hotel") FormChoiceRow(it.value, it.schemaLabel, false, it.label) else it }
            .map { if (it.value == "park") FormChoiceRow(it.value, it.schemaLabel, true, " Outdoors ") else it }
        assertEquals(
            mapOf(
                SL.field to "venue", SL.label to "Venue", SL.required to true,
                SL.choices to listOf(
                    mapOf(SL.value to "office", SL.label to "At the office"),
                    mapOf(SL.value to "park", SL.label to "Outdoors"),
                ),
            ),
            formEntryFrom(start, "venue", copy, required = true, rows = fewer),
        )
        // A relabel alone customizes the list too; a label cleared takes the schema's back.
        val relabeled = everything.map { if (it.value == "office") FormChoiceRow(it.value, it.schemaLabel, true, "Head office") else it }
            .map { if (it.value == "hotel") FormChoiceRow(it.value, it.schemaLabel, true, "  ") else it }
        assertEquals(
            listOf("Head office", "A hotel", "A park"),
            formEntryFrom(start, "venue", copy, required = false, rows = relabeled)[SL.choices].toJsonListOfMaps().map { it[SL.label] },
        )
        assertEquals("A form offers at least one choice.", formChoicesProblem(everything.map { FormChoiceRow(it.value, it.schemaLabel, false, "") }))
        assertNull(formChoicesProblem(fewer))
    }

    @Test
    fun aFieldNobodyCouldAlwaysFillInIsNotOfferedRequired() {
        assertTrue(formRequiredUnavailable(requirementTypes.properties.getValue("note"))!!.contains("cannot see"))
        assertTrue(formRequiredUnavailable(requirementTypes.properties.getValue("total"))!!.contains("works this field out"))
        assertNull(formRequiredUnavailable(requirementTypes.properties.getValue("venue")))
    }
}
