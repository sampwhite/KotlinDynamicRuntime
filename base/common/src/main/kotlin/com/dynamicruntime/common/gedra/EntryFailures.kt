package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.toWireMap
import com.dynamicruntime.common.util.fmt
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * A failure of the entry of trait [traitId] a write would store (issue #1918), its path from the entry (`data.title`). [key]
 * is the entry's, each key field with its value, for a trait that may have several.
 */
class EntryFailure(
    val traitId: String,
    val failure: SchFailure,
    val key: Map<String, Any?> = emptyMap(),
    val more: Map<String, Any?> = emptyMap(),
) {
    /** The failure on the wire: a reported failure's shape, with its trait, as a workflow's problems have it, and its key. */
    fun toWireMap(): Map<String, Any?> = buildMap {
        putAll(failure.toWireMap())
        put(GE.traitId, traitId)
        if (key.isNotEmpty()) put(EP.failureEntryKey, key)
        putAll(more)
    }

    /** The failure for a reader with no form in front of them: the trait and its key, where in its entry, and what is wrong. */
    val text: String get() {
        val which = if (key.isEmpty()) "" else key.values.joinToString(", ", " (", ")") { it.fmt() }
        return "$traitId$which, ${failure.path}: ${failure.message}"
    }

    companion object {
        /** [failure] of [entry], a stored entry, its key read by the types of [union]. */
        fun of(union: SchType?, entry: Map<String, Any?>, failure: SchFailure): EntryFailure {
            val traitId = entry[GE.traitId].toOptStr().orEmpty()
            val data = entry[GE.data].toJsonMapOrEmpty()
            return EntryFailure(traitId, failure, traitKeyIn(union, traitId).associateWith { data[it] }.filterValues { it != null })
        }
    }
}

/**
 * A 400 for a write [failures] refuse (issue #1918): [lead], then each failure's text, with the failures themselves under
 * [EP.failures] of its extra data. A page shows each by the label its own form gives the field, or under the field; a
 * program reads the trait and the path.
 */
fun entryFailuresException(lead: String, failures: List<EntryFailure>): KdrException =
    KdrException.mkInput(listOf(lead, failures.joinToString("; ") { it.text }).filter { it.isNotEmpty() }.joinToString(" ")).also {
        it.extraData[EP.failures] = failures.map { f -> f.toWireMap() }
    }
