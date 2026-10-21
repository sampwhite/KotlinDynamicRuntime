package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.util.humanizeFieldName
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * The global traits a client includes, chosen on its page (issue #1117, `ClientTraitsSection`): the platform's traits
 * one by one, and above them one switch for every global trait, now and to come ([CLD.allGlobal]).
 *
 * Everything here is pure, and covered under `jsNodeTest`: how the catalog is read, how a trait reads in the picker,
 * and the list a save sends ([includedTraitsAfter]).
 */

/** One global trait the client may include, as the catalog answers it: as the platform declares it. */
class TraitChoiceView(
    val traitId: String,
    val description: String?,
    /** The kinds of gedra that may carry an entry of it, by name. */
    val appliesTo: List<String>,
    /** Its data's schema reference into [TraitCatalogView.defs]: its fields. */
    val dataRef: String?,
    /** Whether the client alters or extends it, which makes it supported without being included. */
    val customized: Boolean,
)

/** The catalog the picker draws: the [traits], the schema of their fields, and why groups are refused, if they are. */
class TraitCatalogView(
    val client: String,
    val traits: List<TraitChoiceView>,
    val defs: Map<String, Any?>,
    val groupsRefused: String?,
)

/** The catalog as `/clientAdmin/client/traits` answers it. */
fun parseTraitCatalog(raw: Map<String, Any?>): TraitCatalogView = TraitCatalogView(
    client = raw[CLD.client].toOptStr().orEmpty(),
    traits = raw[CIT.traits].toJsonListOfMaps().map {
        TraitChoiceView(
            traitId = it[CCT.traitId].toOptStr().orEmpty(),
            description = it[CCT.description].toOptStr(),
            appliesTo = it[CCT.appliesTo].toJsonListOfStrings(),
            dataRef = it[CIT.dataRef].toOptStr(),
            customized = it[CIT.customized] == true,
        )
    },
    defs = raw[SCH.dDefs].toJsonMapOrEmpty(),
    groupsRefused = raw[CIT.groupsRefused].toOptStr(),
)

/** The picker's choices: the traits ticked one by one, and whether every global trait is included. */
data class TraitsDraft(val chosen: Set<String>, val allGlobal: Boolean)

/** The draft a client's stored [included] traits start the picker at. */
fun traitsDraftOf(included: List<String>): TraitsDraft =
    TraitsDraft(included.filterNot { it.startsWith(CLD.groupSigil) }.toSet(), CLD.allGlobal in included)

/**
 * The included traits a save sends: [stored] with the picker's changes made in place. An entry the picker shows --
 * a trait of [traits], or [CLD.allGlobal] -- stays where it is while chosen, and goes when not; one newly chosen joins
 * at the end, in the catalog's order. An entry the picker does not show is kept as it is: a trait a code change
 * removed is the load's to drop, with an issue saying so, not something a save of another trait takes away unseen.
 */
fun includedTraitsAfter(stored: List<String>, draft: TraitsDraft, traits: List<TraitChoiceView>): List<String> {
    val shown = traits.map { it.traitId } + CLD.allGlobal
    val wanted = (traits.map { it.traitId }.filter { it in draft.chosen } + listOfNotNull(CLD.allGlobal.takeIf { draft.allGlobal })).toSet()
    val kept = stored.filter { it !in shown || it in wanted }
    return kept + shown.filter { it in wanted && it !in stored }
}

/** Whether a trait shows as included: chosen, under every global trait, or customized. */
fun traitShownIncluded(trait: TraitChoiceView, draft: TraitsDraft): Boolean =
    trait.customized || draft.allGlobal || trait.traitId in draft.chosen

/** Why a trait's own box cannot be changed, or null when it can: the box would change nothing the client supports. */
fun traitLockedReason(trait: TraitChoiceView, draft: TraitsDraft): String? = when {
    trait.customized -> "This client customizes it, so it is supported without being included."
    draft.allGlobal -> "Included with every global trait."
    else -> null
}

/**
 * Why the switch for every global trait cannot be turned on, or null when it can. Only on: a client already holding
 * the group may always let it go.
 */
fun allGlobalLockedReason(catalog: TraitCatalogView, draft: TraitsDraft): String? =
    catalog.groupsRefused?.takeIf { !draft.allGlobal }

/** The kinds a trait applies to, in words: "Form doc, User data". */
fun traitAppliesToText(appliesTo: List<String>): String = appliesTo.joinToString(", ") { humanizeFieldName(it) }

/** What the section says above the picker, before anyone acts. */
const val traitsIntro = "The global traits this client's forms and data may use. Its own traits, and those it " +
    "customizes, are supported without being included here."

/** The save's request: the whole list, how the page expects it to take effect, and whether an impact is acknowledged. */
fun traitsEditRequest(clientId: String, included: List<String>, expected: String, acknowledge: Boolean): Map<String, Any?> = buildMap {
    put(CLD.client, clientId)
    put(CLD.includedTraits, included)
    put(CPY.mode, expected)
    if (acknowledge) put(IMP.acknowledgeImpact, true)
}

/** What a save did: the configuration it landed in, the traits now stored, and how it took effect. */
class TraitsSetResult(val configName: String, val included: List<String>, val mode: String)

fun parseTraitsSetResult(raw: Map<String, Any?>): TraitsSetResult = TraitsSetResult(
    configName = raw[COV.configName].toOptStr().orEmpty(),
    included = raw[CLD.includedTraits].toJsonListOfStrings(),
    mode = raw[CPY.mode].toOptStr().orEmpty(),
)
