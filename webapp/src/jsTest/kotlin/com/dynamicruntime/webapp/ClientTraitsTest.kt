package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.schema.SCH
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic coverage (issue #161) for the traits a client includes, chosen on its page (issue #1117): how the catalog
 * is read, how a trait reads in the picker, and the list a save sends.
 */
class ClientTraitsTest {
    private fun trait(id: String, customized: Boolean = false) = TraitChoiceView(id, "The $id trait.", listOf("formDoc"), null, customized)

    private val traits = listOf(trait("kdr:name"), trait("kdr:address"), trait("kdr:phone"))

    @Test
    fun readsTheCatalog() {
        val catalog = parseTraitCatalog(
            mapOf(
                CLD.client to "acme",
                CIT.traits to listOf(
                    mapOf(
                        CCT.traitId to "kdr:name", CCT.description to "A name.", CCT.appliesTo to listOf("formDoc", "userData"),
                        CIT.dataRef to "#/\$defs/kdr.core.NameData", CIT.customized to true,
                    ),
                    mapOf(CCT.traitId to "kdr:address", CCT.appliesTo to listOf("formDoc"), CIT.customized to false),
                ),
                SCH.dDefs to mapOf("kdr.core.NameData" to mapOf(SCH.type to "object")),
                CIT.groupsRefused to "Names each trait.",
            ),
        )
        assertEquals("acme", catalog.client)
        assertEquals(listOf("kdr:name", "kdr:address"), catalog.traits.map { it.traitId })
        val name = catalog.traits.first()
        assertEquals("A name.", name.description)
        assertEquals("#/\$defs/kdr.core.NameData", name.dataRef)
        assertTrue(name.customized)
        assertNull(catalog.traits[1].description)
        assertEquals(setOf("kdr.core.NameData"), catalog.defs.keys)
        assertEquals("Names each trait.", catalog.groupsRefused)
        assertEquals("Form doc, User data", traitAppliesToText(name.appliesTo))
    }

    @Test
    fun aDraftStartsFromWhatIsStored() {
        assertEquals(TraitsDraft(setOf("kdr:name"), allGlobal = true), traitsDraftOf(listOf("kdr:name", CLD.allGlobal)))
        assertEquals(TraitsDraft(emptySet(), allGlobal = false), traitsDraftOf(emptyList()))
    }

    @Test
    fun aSaveChangesTheListInPlace() {
        val stored = listOf("kdr:phone", "kdr:name")
        // Nothing changed: the same list, in its order.
        assertEquals(stored, includedTraitsAfter(stored, traitsDraftOf(stored), traits))
        // One taken away stays out; one added joins at the end, in the catalog's order.
        val draft = TraitsDraft(setOf("kdr:phone", "kdr:address"), allGlobal = false)
        assertEquals(listOf("kdr:phone", "kdr:address"), includedTraitsAfter(stored, draft, traits))
        // The group joins after the traits, and turning it off keeps the traits ticked one by one.
        val everything = includedTraitsAfter(stored, traitsDraftOf(stored).copy(allGlobal = true), traits)
        assertEquals(listOf("kdr:phone", "kdr:name", CLD.allGlobal), everything)
        assertEquals(stored, includedTraitsAfter(everything, traitsDraftOf(everything).copy(allGlobal = false), traits))
    }

    @Test
    fun anEntryThePickerDoesNotShowIsKept() {
        // A trait a code change removed: the load drops it with an issue, and a save of another trait leaves it be.
        val stored = listOf("kdr:gone", "kdr:name")
        val draft = TraitsDraft(setOf("kdr:address"), allGlobal = false)
        assertEquals(listOf("kdr:gone", "kdr:address"), includedTraitsAfter(stored, draft, traits))
    }

    @Test
    fun aTraitTheClientCustomizesOrTheGroupIncludesIsLocked() {
        val draft = TraitsDraft(emptySet(), allGlobal = false)
        val customized = trait("kdr:name", customized = true)
        assertTrue(traitShownIncluded(customized, draft))
        assertNotNull(traitLockedReason(customized, draft))
        assertFalse(traitShownIncluded(traits[1], draft))
        assertNull(traitLockedReason(traits[1], draft))
        val everything = draft.copy(allGlobal = true)
        assertTrue(traitShownIncluded(traits[1], everything))
        assertEquals("Included with every global trait.", traitLockedReason(traits[1], everything))
    }

    @Test
    fun theGroupIsLockedOffOnlyWhereItIsRefused() {
        val refused = TraitCatalogView("acme", traits, emptyMap(), groupsRefused = "Names each trait.")
        assertEquals("Names each trait.", allGlobalLockedReason(refused, TraitsDraft(emptySet(), allGlobal = false)))
        // A client holding it may always let it go.
        assertNull(allGlobalLockedReason(refused, TraitsDraft(emptySet(), allGlobal = true)))
        assertNull(allGlobalLockedReason(TraitCatalogView("acme", traits, emptyMap(), null), TraitsDraft(emptySet(), false)))
    }

    private fun templateCatalog(included: List<String>, own: List<Map<String, Any?>> = emptyList()) = parseTraitCatalog(
        mapOf(
            CLD.client to "free_x",
            CIT.traits to traits.map { mapOf(CCT.traitId to it.traitId, CCT.appliesTo to listOf("formDoc")) },
            CIT.template to "starterkit",
            CIT.templateIncluded to included,
            CIT.ownTraits to own,
        ),
    )

    @Test
    fun aTraitTheTemplateIncludesIsDrawnIncludedAndLockedNamingIt() {
        // Issue #1621: the client has it whatever it stores, so its box is ticked and cannot be changed.
        val catalog = templateCatalog(listOf("kdr:name", "sample:questionnaire"))
        assertEquals("starterkit", catalog.template)
        val (name, address) = catalog.traits
        assertEquals("starterkit", name.includedBy)
        assertNull(address.includedBy)
        val none = TraitsDraft(emptySet(), allGlobal = false)
        assertTrue(traitShownIncluded(name, none))
        assertEquals("Included by its template, starterkit: a client built on it always has it.", traitLockedReason(name, none))
        assertFalse(traitShownIncluded(address, none))
        assertNull(traitLockedReason(address, none))
        // Only the picker's own traits count: one the catalog does not show is the template's business.
        assertEquals(setOf("kdr:name"), catalog.byTemplate)
        assertFalse(catalog.templateIncludesAllGlobal)
        // A template field without a template is not one.
        val stray = parseTraitCatalog(
            mapOf(CLD.client to "acme", CIT.traits to listOf(mapOf(CCT.traitId to "kdr:name")), CIT.templateIncluded to listOf("kdr:name")),
        )
        assertNull(stray.template)
        assertNull(stray.traits.single().includedBy)
    }

    @Test
    fun aSaveNeverStoresWhatTheTemplateIncludes() {
        val catalog = templateCatalog(listOf("kdr:name"))
        // Chosen alongside the template's (a draft seeded from a stored duplicate holds it): only the client's own is added.
        val draft = TraitsDraft(setOf("kdr:name", "kdr:phone"), allGlobal = false)
        assertEquals(listOf("kdr:phone"), includedTraitsAfter(emptyList(), draft, catalog.traits, catalog.byTemplate))
        // A duplicate the client already stores is kept as it is, so nothing changes unseen.
        val stored = listOf("kdr:name")
        assertEquals(stored, includedTraitsAfter(stored, traitsDraftOf(stored), catalog.traits, catalog.byTemplate))
        assertEquals(stored, includedTraitsAfter(stored, TraitsDraft(emptySet(), allGlobal = false), catalog.traits, catalog.byTemplate))
    }

    @Test
    fun aTemplateIncludingEveryGlobalTraitLocksTheSwitchOn() {
        val catalog = templateCatalog(listOf(CLD.allGlobal))
        val none = TraitsDraft(emptySet(), allGlobal = false)
        assertTrue(catalog.templateIncludesAllGlobal)
        assertTrue(allGlobalShownOn(catalog, none))
        assertEquals(
            "Included by its template, starterkit: a client built on it always has every global trait.",
            allGlobalLockedReason(catalog, none),
        )
        assertTrue(catalog.traits.all { it.includedBy == "starterkit" })
        // The group is never stored for it either.
        assertEquals(emptyList(), includedTraitsAfter(emptyList(), none.copy(allGlobal = true), catalog.traits, catalog.byTemplate))
        assertFalse(allGlobalShownOn(templateCatalog(emptyList()), none))
    }

    @Test
    fun anOwnTraitFromTheTemplateSaysSo() {
        val catalog = templateCatalog(
            emptyList(),
            own = listOf(
                mapOf(CIT.traitId to "mine", CIT.configName to "trait_mine"),
                mapOf(CIT.traitId to "coded", CIT.notEditable to "It is declared in code, where it is edited."),
                mapOf(CIT.traitId to "pubNote", CIT.fromTemplate to "starterkit", CIT.notEditable to "From its template, starterkit."),
            ),
        )
        assertEquals(listOf("trait_mine", "code", "template starterkit"), catalog.own.map(::ownTraitDefinedIn))
        assertEquals("starterkit", catalog.own.last().fromTemplate)
        assertNull(catalog.own.first().fromTemplate)
    }

    @Test
    fun theRequestSaysHowItTakesEffectAndWhetherTheImpactIsSeen() {
        val request = traitsEditRequest("acme", listOf("kdr:name"), EDM.draft, acknowledge = false)
        assertEquals(mapOf(CLD.client to "acme", CLD.includedTraits to listOf("kdr:name"), CPY.mode to EDM.draft), request)
        assertEquals(true, traitsEditRequest("acme", emptyList(), EDM.live, acknowledge = true)[IMP.acknowledgeImpact])
    }
}
