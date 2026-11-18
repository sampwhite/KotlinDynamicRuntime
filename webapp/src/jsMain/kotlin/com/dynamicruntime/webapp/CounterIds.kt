package com.dynamicruntime.webapp

import com.dynamicruntime.common.counter.CTR
import com.dynamicruntime.common.counter.counterFieldsOf
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import react.StateSetter
import react.useEffect
import react.useState

/** An id a counter issued (issue #1849): the [value] its field shows, and the [token] the save sends back to store it. */
class IssuedId(val value: String, val token: String)

/** Asks a client's counters for ids (issue #1849). */
object CounterApi {
    /**
     * The next id of [counterId]: from [client]'s copy of the endpoint when one is named -- a form being made in another
     * client -- else the caller's own client's. Each call uses up an id.
     */
    suspend fun issue(counterId: String, client: String? = null): IssuedId {
        val path = if (client == null) GEP.counterIssue else clientPath(GEP.counterIssue, client)
        val results = Http.sendApi("POST", path, mapOf(CTR.counter to counterId))[EP.results].toJsonMapOrEmpty()
        return IssuedId(results[CTR.value].toOptStr().orEmpty(), results[CTR.token].toOptStr().orEmpty())
    }
}

/** A field a counter fills, on a trait of a form being created: the trait, the field, and the counter (issue #1849). */
data class CounterSlot(val traitId: String, val field: String, val counterId: String)

/**
 * The counter fields of [traits] -- each trait id with its data type -- that [valuesOf] gives no value yet, each trait
 * once: what a create page asks ids for as it opens, so the person sees them before the form is saved. Pure, and
 * covered under `jsNodeTest`.
 */
fun counterSlotsToFill(traits: List<Pair<String, SchType>>, valuesOf: (String) -> Map<String, Any?>?): List<CounterSlot> =
    traits.distinctBy { it.first }.flatMap { (traitId, type) ->
        val values = valuesOf(traitId) ?: return@flatMap emptyList()
        counterFieldsOf(type).filter { (field, _) -> values[field].toOptStr().isNullOrEmpty() }
            .map { (field, counterId) -> CounterSlot(traitId, field, counterId) }
    }

/**
 * The counter fields of [entries] -- a create's `{traitId, data}` entries -- that have no id yet, one slot an entry and
 * field, so two entries of a keyed trait are each given their own. [dataTypeOf] is a trait's data type, or null for one
 * the page does not know. Pure, and covered under `jsNodeTest`.
 */
fun counterSlotsInEntries(entries: List<Map<String, Any?>>, dataTypeOf: (String) -> SchType?): List<CounterSlot> =
    entries.flatMap { entry ->
        val traitId = entry[GE.traitId].toOptStr() ?: return@flatMap emptyList()
        counterSlotsToFill(listOfNotNull(dataTypeOf(traitId)?.let { traitId to it })) { entry[GE.data].toJsonMapOrEmpty() }
    }

/**
 * [entries] -- a create's `{traitId, data}` entries -- with [value] put in [slot]'s field of the first entry of its trait
 * that has none, or as they were when every one has. How an id the page asked for lands where it was asked for, even
 * if the person added or moved sections while it was on its way.
 */
fun withCounterId(entries: List<Map<String, Any?>>, slot: CounterSlot, value: String): List<Map<String, Any?>> {
    val at = entries.indexOfFirst {
        it[GE.traitId] == slot.traitId && it[GE.data].toJsonMapOrEmpty()[slot.field].toOptStr().isNullOrEmpty()
    }
    if (at < 0) return entries
    val entry = entries[at]
    return entries.toMutableList().also { it[at] = entry + (GE.data to (entry[GE.data].toJsonMapOrEmpty() + (slot.field to value))) }
}

/** What [useCounterIds] answers: the tokens a create sends with the ids it was given, and a failure to get one. */
class CounterIds(val tokens: List<String>, val error: DisplayError?)

/**
 * The ids of a create page's counter fields (issue #1849), for a page whose form is a create's `entries` against
 * [inputType] -- the trait picker and the create for another user: each section of a trait a counter numbers is given
 * its id as it is added, so the person sees it before the form is saved, and [values] are updated through
 * [updateValues]. Asked of [client]'s copy of the endpoint when one is named. The ask runs again only when the sections
 * still lacking an id change, not on each keystroke; a section removed while its id is on its way leaves a gap.
 */
fun useCounterIds(
    inputType: SchType?,
    values: Map<String, Any?>,
    updateValues: StateSetter<Map<String, Any?>>,
    client: String? = null,
): CounterIds {
    val tokensState = useState<List<String>>(emptyList())
    val tokens by tokensState
    val updateTokens = tokensState.component2()
    var error by useState<DisplayError?>(null)
    val variants = inputType?.properties?.get(GDF.entries)?.valueType?.itemType?.variants
    val slots = variants?.let { v ->
        counterSlotsInEntries(values[GDF.entries].toJsonListOfMaps()) { v.select(it)?.properties?.get(GE.data)?.valueType }
    }.orEmpty()
    useEffect(slots.joinToString { "${it.traitId}.${it.field}" }, client) {
        if (slots.isEmpty()) return@useEffect
        try {
            // Every id asked for before any lands: one landing changes the slots, which would start the ask over and use up
            // the ids still on their way.
            val issued = slots.map { it to CounterApi.issue(it.counterId, client) }
            updateValues { current ->
                current + (GDF.entries to issued.fold(current[GDF.entries].toJsonListOfMaps()) { acc, (slot, id) -> withCounterId(acc, slot, id.value) })
            }
            updateTokens { it + issued.map { (_, id) -> id.token } }
        } catch (e: Throwable) {
            error = userFacingError(e)
        }
    }
    return CounterIds(tokens, error)
}

/** [payload] with [tokens] beside it, as a create sends the tokens of the ids it stores (issue #1849). */
fun withCounterTokens(payload: Map<String, Any?>, tokens: List<String>): Map<String, Any?> =
    if (tokens.isEmpty()) payload else payload + (CTR.counterTokens to tokens)
