package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Instant

/**
 * One revision row of [GCT.gedraConfig], extracted into typed fields (issue #633) -- the twin of [GedraDataRow]
 * for the config family. A row is one revision of one config: its versioned [gedraId], the [version] the id's
 * suffix carries, whether it has been published, and the config-trait [entries] the revision holds.
 *
 * [entries] is promoted out of the stored [GC.data] map -- keyed under [GD.entries] exactly as a data gedra
 * keys its own, so the two families store an entry list the same way -- and [extra] keeps whatever else that
 * map held, so a node that writes back a row a newer node wrote does not drop the keys it did not know about
 * (the same forward-compatibility move [GedraDataRow] makes).
 *
 * ### version and configId are read from the id, not the columns
 *
 * Both are denormalizations of [gedraId] kept so a query can order and filter by them ([GC.configId] on why),
 * and the id is the authority: [version] comes from `GedraId.revision` and [configId] from
 * `GedraId.revisionClass()`, never from the columns, so a column that somehow drifted could make a row
 * unfindable but never mislabeled -- the same rule [GedraDataRow.kind] follows.
 */
class GedraConfigRow(
    /** This revision's **versioned** identity (`gc.cd.acme.main~3`). */
    val gedraId: GedraId,
    /** The owning client. Denormalized from [gedraId], because a query needs it as a column. */
    val client: String,
) {
    /** The revision class -- the id with no suffix -- that every revision of this config shares. */
    val configId: GedraId = gedraId.revisionClass()

    /** The revision number, read from the id's suffix rather than the column (see the class note). */
    val version: Int = gedraId.revision
        ?: throw KdrException("'$gedraId' carries no revision, so it does not name a row of ${GCT.gedraConfig}.")

    /** When this revision was published, or null while it is the editable latest ([GC.publishedAt]). */
    var publishedAt: Instant? = null

    /** Whether the row is live; a disabled revision is never handed back (see [PF.enabled]). */
    var enabled: Boolean = false

    /** The config-trait entries this revision holds, each an envelope over one slot's data (see [entriesBySlot]). */
    var entries: List<Map<String, Any?>> = emptyList()

    /**
     * The namespace the config's generated types live in (issue #614), read from [GC.namespace] in the stored
     * map. Empty for a row written before the namespace was persisted, or one that never carried it; the boot
     * loader falls back to recovering it from a stored qualified type name in that case.
     */
    var namespace: String = ""

    /** Whatever else the stored [GC.data] map held, [GD.entries] and [GC.namespace] promoted out. */
    var extra: Map<String, Any?> = emptyMap()

    var createdAt: Instant? = null
    var updatedAt: Instant? = null

    /** Whether this revision has been published, i.e. is no longer the editable latest. */
    val isPublished: Boolean get() = publishedAt != null

    /**
     * The revision's entries grouped by slot -- trait id ([GE.traitId]) to the list of each entry's own
     * [GE.data] -- which is exactly the `entriesBySlot` shape [reassembleGedraConfig] takes. The read-back
     * bridge from a stored row to a [GedraConfig]; the boot loader (#614) turns this into a config.
     */
    fun entriesBySlot(): Map<String, List<Map<String, Any?>>> {
        val out = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
        for (entry in entries) {
            val slot = entry[GE.traitId].toOptStr() ?: continue
            out.getOrPut(slot) { mutableListOf() }.add(entry[GE.data].toJsonMapOrEmpty())
        }
        return out
    }

    /**
     * The [GC.data] map to store for this row with [entries] in place of the current ones: the keys this node
     * does not know ([extra]) and the entries under [GD.entries]. The one place the stored map is assembled, so
     * promoting a key out of [extra] cannot quietly drop it on the next write (as [GedraDataRow.storedData] does).
     */
    fun storedData(entries: List<Map<String, Any?>>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(extra)
        if (namespace.isNotEmpty()) out[GC.namespace] = namespace
        out[GD.entries] = entries
        return out
    }

    companion object {
        /**
         * Builds a typed row from a stored [GCT.gedraConfig] map, taking the shared instance of its id from
         * [gedraService] so every reader of one revision holds the same [GedraId] object.
         */
        fun extract(gedraService: GedraService, data: Map<String, Any?>): GedraConfigRow =
            extract(data) { gedraService.readId(it) }

        /**
         * As [extract], but resolving the id through [idOf] rather than [GedraService] -- for the boot loader
         * (issue #614), which runs in the startup tier before [GedraService] (a regular service) exists and so
         * passes `GedraId::parse`. Interning is only a shared-instance optimization; a parsed id is a correct
         * id, so the load path loses nothing but the interning.
         */
        fun extract(data: Map<String, Any?>, idOf: (String) -> GedraId): GedraConfigRow {
            val fullId = data[GC.gedraId].toOptStr()
                ?: throw KdrException("A ${GCT.gedraConfig} row is missing its ${GC.gedraId}.")
            val row = GedraConfigRow(
                gedraId = idOf(fullId),
                client = data[PF.client].toOptStr() ?: "",
            )
            row.publishedAt = data[GC.publishedAt].toOptInstant()
            row.enabled = data[PF.enabled] == true
            val stored = data[GC.data].toJsonMapOrEmpty()
            row.entries = stored[GD.entries].toJsonListOfMaps()
            row.namespace = stored[GC.namespace].toOptStr() ?: ""
            row.extra = stored - GD.entries - GC.namespace
            row.createdAt = data[PF.createdAt].toOptInstant()
            row.updatedAt = data[PF.updatedAt].toOptInstant()
            return row
        }
    }
}
