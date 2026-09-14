package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.util.mkUniqueId
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Instant

/**
 * One trait entry stamped for storage (issues #626, #633): a new entry gets a fresh envelope; a changed one
 * keeps who first wrote it and when, and moves only its `updated` half to [now] -- the whole reason the envelope
 * carries both the created and updated pairs.
 *
 * The single stamping both write families share -- a data gedra's traits (`GedraDataService`) and a stored
 * config's slots (`GedraConfigService`), which is why it is a free function here rather than a private on either.
 * [traitId] is the slot or trait the envelope records under [GE.traitId]; the two differ only in what they call
 * that value.
 */
fun mkStoredEntry(
    cxt: KdrCxt,
    traitId: String,
    data: Map<String, Any?>,
    existing: Map<String, Any?>?,
    now: Instant,
): Map<String, Any?> {
    val actor = cxt.userProfile.userId
    val base = linkedMapOf<String, Any?>(GE.traitId to traitId, GE.data to data)
    if (existing == null) {
        return base.asStoredEntry(cxt.mkUniqueId(), GSRC.user, now, actor)
    }
    return base.asStoredEntry(
        entryId = existing[GE.entryId].toOptStr() ?: cxt.mkUniqueId(),
        source = GSRC.user,
        createdAt = existing[GE.createdAt].toOptInstant() ?: now,
        createdBy = existing[GE.createdBy].toOptLong() ?: actor,
        updatedAt = now,
        updatedBy = actor,
    )
}
