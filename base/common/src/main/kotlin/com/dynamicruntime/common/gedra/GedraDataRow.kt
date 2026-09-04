package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.WfRef
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypesBuilder
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Instant

// `GDF` (the wire field-names for a gedra) now lives in `base/kernel` (GedraConstants.kt) so a front end can
// read `entries`/`gedraId` off a response by name too (issue #393). References here resolve unchanged.

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

    /**
     * The creation workflow that made this gedra, or null when none did (issue #533) -- configuration
     * lineage: which definition, at which bundle revision, decided this document's shape. Promoted out of
     * [extra] like [entries], so a writer reassembles the map through [storedData] rather than by hand.
     *
     * Read **leniently**: a stored value that is not a reference reads as null rather than failing the row,
     * because an audit field must never make a document unreadable.
     */
    var creationWorkflowId: WfRef? = null

    /**
     * Whatever else the stored [GD.data] map held, [GD.entries] promoted out. Nothing writes a key here yet, and
     * the patch still carries it through **unchanged** -- that is the forward-compatibility promise above, and
     * it is kept by a test rather than by a producer, so that the merge is not "simplified" away as dead.
     */
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
        GDF.creationWorkflowId to creationWorkflowId?.text,
        GDF.createdAt to createdAt,
        GDF.updatedAt to updatedAt,
    )

    /**
     * The [GD.data] map to store for this row with [entries] in place of the current ones: the keys this node
     * does not know ([extra]), the ones it promoted out ([creationWorkflowId]), and the entries. The one place
     * the stored map is assembled, so promoting a key out of [extra] cannot quietly drop it on the next write.
     */
    fun storedData(entries: List<Map<String, Any?>>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(extra)
        creationWorkflowId?.let { out[GD.creationWorkflowId] = it.text }
        out[GD.entries] = entries
        return out
    }

    @Suppress("ConstPropertyName")
    companion object {
        /**
         * What the `allowAdditionalTraits` field says, written once so the type ([defineType]) and the patch
         * endpoint cannot come to describe the same flag differently. Beside the type that owns the field, as a
         * `const` so it inlines at both sites; not a key, so it belongs to this type rather than to an acronym
         * vocabulary.
         */
        const val additionalTraitsHint =
            "Whether this call may write traits the client does not support. Defaults to false, so a misspelled " +
                "trait id -- or one belonging to another client -- is refused rather than stored as an unrecognized " +
                "shape. Reads are unaffected."

        /**
         * Defines the schema types for gedras of one [kind] -- `FormDoc` for [GedraDataType.formDoc] -- on
         * [builder], beside the [toJsonMap] they describe so the three cannot drift: **the stored shape, and
         * beside it the shape a caller sends** (issue #379).
         *
         * Its `entries` refer to the **manufactured** union for the same kind, which does not exist when this
         * runs and is an ordinary type by the time anything resolves the reference. That is the arrangement the
         * fixture in `sample` already proved out.
         *
         * Two types rather than one, because they are not the same thing. Everything a gedra *is* appears in
         * both; `allowAdditionalTraits` is an instruction about a write, so it appears only in the second --
         * it has no business in the answer to `GET /gedra/formDocs`, which describes documents rather than
         * requests.
         *
         * They are declared together, from one body, so they cannot drift: a field added to a gedra reaches
         * both without anybody remembering to. The stored type stays the one a caller may echo back whole --
         * every field it does not own is `g-derived` and dropped by the input projection -- which is why the
         * input type is the stored one *plus* a field, and not a smaller type of its own.
         */
        fun defineType(builder: SchTypesBuilder, kind: GedraDataType) {
            defineOneType(builder, kind, GU.gedraName(kind), forInput = false)
            defineOneType(builder, kind, GU.inputName(kind), forInput = true)
        }

        private fun defineOneType(
            builder: SchTypesBuilder,
            kind: GedraDataType,
            typeName: String,
            forInput: Boolean,
        ) {
            val entryRef = "${GCFG.globalNamespace}.${GU.unionName(kind)}"
            builder.type(typeName) {
                type = SCT.kObject
                description = if (forInput) {
                    "A ${kind.name} gedra as a caller sends one."
                } else {
                    "One stored ${kind.name} gedra and the entries it carries."
                }
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
                property(
                    GDF.creationWorkflowId,
                    "The creation workflow that made this gedra, as a workflow reference; absent when none did.",
                ) {
                    derived = true
                }
                property(GDF.displayValues, "Computed display values from the client's trait-usage rules (issue #537).") {
                    type = SCT.array
                    items { type = SCT.kObject }
                    derived = true
                }
                // The owner as a block of user-type information (issue #580, was two flat keys in #562): the
                // email, and a display name when it adds one. Attached to a *listed* row for a caller who may
                // see other users' documents; absent for an ordinary caller and on a single read. Open, since
                // more user-block fields are coming and only the ones a caller may see are ever present.
                property(GDF.owner, "The owning user (email, and a display name when it is not the email), when the caller may see other users' documents.") {
                    type = SCT.kObject
                    derived = true
                    userBlockProperties()
                }
                // An instruction about the write, so it belongs to the sent shape and to nothing else.
                if (forInput) {
                    property(GDF.allowAdditionalTraits, additionalTraitsHint) { type = SCT.boolean }
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
            row.creationWorkflowId = WfRef.parseOrNull(stored[GD.creationWorkflowId].toOptStr())
            row.extra = stored - GD.entries - GD.creationWorkflowId
            row.createdAt = data[PF.createdAt].toOptInstant()
            row.updatedAt = data[PF.updatedAt].toOptInstant()
            return row
        }
    }
}
