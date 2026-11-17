package com.dynamicruntime.webapp

import com.dynamicruntime.common.counter.CTR
import com.dynamicruntime.common.counter.counterFieldsOf
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

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
