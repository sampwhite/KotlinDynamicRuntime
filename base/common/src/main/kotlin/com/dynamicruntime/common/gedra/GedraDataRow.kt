package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypesBuilder
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Instant

/** Field names for a gedra's wire shape (see [GedraDataRow.toJsonMap]). Each name matches its value. */
@Suppress("ConstPropertyName")
object GDF {
    const val gedraId = "gedraId"
    const val gedraKind = "gedraKind"
    const val client = "client"
    const val userId = "userId"
    const val org = "org"
    const val entries = "entries"
    const val createdAt = "createdAt"
    const val updatedAt = "updatedAt"
}

/**
 * One row of [GDT.gedraData], extracted into typed fields (issue #310) -- the richer aggregate the vocabulary
 * calls a *gedra*, as against the storage map it came from.
 *
 * [entries] is promoted out of the stored [GD.data] map and [extra] keeps whatever else that map held, so a
 * caller that reads a gedra written by a newer node and writes it back does not silently drop the keys it did
 * not know about. That is the same move `AuthUserRow` makes with `authUserData`, and it matters more here:
 * [GD.data] is explicitly the place future capabilities land.
 */
class GedraDataRow(
    /** This gedra's identity. */
    val gedraId: GedraId,
    /** The owning client. Denormalized from [gedraId], because a query needs it as a column. */
    val client: String,
    /** The owning user. */
    val userId: Long,
) {
    /**
     * What this gedra is, **read from the id** rather than from the `gedraKind` column.
     *
     * The id cannot disagree with itself, and the column is a denormalization kept for querying (see
     * [GD.gedraKind]), so taking the kind from the id means a column that somehow drifted could make a row
     * unfindable but never mislabeled.
     */
    val kind: GedraDataType = gedraId.dataType
        ?: throw KdrException("'$gedraId' is a config id, so it does not name a row of ${GDT.gedraData}.")

    /**
     * The owning organization within [client], or null when the gedra belongs to the client as a whole.
     *
     * **Unlike [client] and [userId], this one moves.** A gedra can be reassigned between organizations
     * inside its client, so this is real mutable state rather than a stamp — see the ownership note on
     * `gedraDataTables`. Nothing writes it back yet, because nothing updates a gedra at all; the "write" path
     * that does has to carry it *explicitly*, since `SqlTopicUtil.prepForStdExecute` fills the column
     * put-if-absent and would otherwise preserve whatever is already stored.
     */
    var org: String? = null

    /** Whether the row is live; a disabled gedra is never handed back (see [PF.enabled]). */
    var enabled: Boolean = false

    /** The gedra's entries, each an instance of the trait its `traitId` names. */
    var entries: List<Map<String, Any?>> = emptyList()

    /** Whatever else the stored [GD.data] map held, [GD.entries] promoted out. Empty today. */
    var extra: Map<String, Any?> = emptyMap()

    var createdAt: Instant? = null
    var updatedAt: Instant? = null

    /** The gedra's wire shape -- what [defineType] describes. */
    fun toJsonMap(): Map<String, Any?> = linkedMapOf(
        GDF.gedraId to gedraId.fullId,
        GDF.gedraKind to kind.name,
        GDF.client to client,
        GDF.userId to userId,
        GDF.org to org,
        GDF.entries to entries,
        GDF.createdAt to createdAt,
        GDF.updatedAt to updatedAt,
    )

    companion object {
        /**
         * Defines the schema type for gedras of one [kind] -- `FormDoc` for [GedraDataType.formDoc] -- on
         * [builder], beside the [toJsonMap] it describes so the two cannot drift.
         *
         * Its `entries` refer to the **manufactured** union for the same kind, which does not exist when this
         * runs and is an ordinary type by the time anything resolves the reference. That is the arrangement the
         * fixture in `sample` already proved out.
         *
         * Everything except `entries` is `g-derived`, which is what lets one type serve both directions: the
         * endpoint builder's input projection drops derived fields, so a caller creating a gedra supplies its
         * entries and nothing else, while the output carries the whole thing. Two types would be two places to
         * add a field to.
         */
        fun defineType(builder: SchTypesBuilder, kind: GedraDataType) {
            val entryRef = "${GCFG.globalNamespace}.${GU.unionName(kind)}"
            builder.type(GU.gedraName(kind)) {
                type = SCT.kObject
                description = "One stored ${kind.name} gedra and the entries it carries."
                property(GDF.gedraId, "This gedra's id.", required = true) { derived = true }
                property(GDF.gedraKind, "What this gedra is.", required = true) { derived = true }
                property(GDF.client, "The owning client.", required = true) { derived = true }
                property(GDF.userId, "The owning user.", required = true) {
                    type = SCT.integer
                    derived = true
                }
                property(GDF.org, "The owning organization within the client, when there is one.") {
                    derived = true
                }
                property(GDF.entries, "The entries this gedra carries.", required = true) {
                    type = SCT.array
                    items { ref(entryRef) }
                }
                property(GDF.createdAt, "When the gedra was created.", required = true) {
                    dateTime()
                    derived = true
                }
                property(GDF.updatedAt, "When the gedra was last written.", required = true) {
                    dateTime()
                    derived = true
                }
            }
        }

        /**
         * Builds a typed row from a stored [GDT.gedraData] map, taking the shared instance of its id from
         * [gedraService] so that every row of one gedra holds the same [GedraId] object.
         */
        fun extract(gedraService: GedraService, data: Map<String, Any?>): GedraDataRow {
            val fullId = data[GD.gedraId].toOptStr()
                ?: throw KdrException("A ${GDT.gedraData} row is missing its ${GD.gedraId}.")
            val row = GedraDataRow(
                gedraId = gedraService.readId(fullId),
                client = data[PF.client].toOptStr() ?: "",
                userId = data[PF.userId].toOptLong() ?: 0L,
            )
            row.org = data[PF.org].toOptStr()?.ifEmpty { null }
            row.enabled = data[PF.enabled] == true
            val stored = data[GD.data].toJsonMapOrEmpty()
            row.entries = stored[GD.entries].toJsonListOfMaps()
            row.extra = stored - GD.entries
            row.createdAt = data[PF.createdAt].toOptInstant()
            row.updatedAt = data[PF.updatedAt].toOptInstant()
            return row
        }
    }
}
