package com.dynamicruntime.common.user

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Instant

/**
 * A row of `AuthIdentities` (issue #747): the **person behind a login** -- keyed by a random [identityId] and
 * reachable by the one normalized address [primaryId] -- as distinct from the `AuthUsers` rows that person
 * acts *as*, each in one client with one persona. Phase A of the identity split: every identity has exactly
 * one user, so nothing visible changes; the row exists so that credentials (phase B), switching (C) and
 * invitations (E) have somewhere to live that is not a user.
 *
 * Mutable, like [AuthUserRow], and written back whole by `UserService.updateIdentity`; [data] is the stored
 * row for the write, [identityData] the blob the later phases fill.
 */
class AuthIdentityRow(val identityId: String,
                      /** The identity's normalized address; rewritten only when the identity is retired (`deletedPrimaryId`). */
                      var primaryId: String
) {
    /**
     * When the address was **proven** -- a verification code read from the inbox, or a Google-verified sign-in
     * -- or null while nobody has. An identity an administrator created for an address nobody has registered
     * is unverified until the person first proves it (phase E's invitation).
     */
    var verifiedAt: Instant? = null

    /** The user this identity logs in as by choice, or null for the fallback (`UserService.defaultUserOf`). */
    var defaultUserId: Long? = null

    /** The user this identity most recently acted as; the first fallback when no default is chosen. */
    var lastUsedUserId: Long? = null

    /** The identity's own data blob: what phase B moves here (password, contacts). Empty in phase A. */
    var identityData: MutableMap<String, Any?> = mutableMapOf()

    /** When the row was last written; the version `updateIdentity` guards on. */
    var updatedAt: Instant? = null

    /** The raw stored row, for callers that mutate and write it back. */
    var data: Map<String, Any?> = emptyMap()

    /** Repackages the typed fields into a storage map. */
    fun toMap(): Map<String, Any?> {
        val out = data.toMutableMap()
        out[AI.identityId] = identityId
        out[AI.primaryId] = primaryId
        out[AI.verifiedAt] = verifiedAt
        out[AI.defaultUserId] = defaultUserId
        out[AI.lastUsedUserId] = lastUsedUserId
        out[AI.identityData] = identityData
        return out
    }

    companion object {
        /** Builds a typed row from a stored `AuthIdentities` map. */
        fun extract(data: Map<String, Any?>): AuthIdentityRow {
            val id = data[AI.identityId].toOptStr() ?: throw KdrException("AuthIdentities row is missing its identityId.")
            val address = data[AI.primaryId].toOptStr() ?: throw KdrException("AuthIdentities row is missing its primaryId.")
            val row = AuthIdentityRow(id, address)
            row.verifiedAt = data[AI.verifiedAt].toOptInstant()
            row.defaultUserId = data[AI.defaultUserId].toOptLong()
            row.lastUsedUserId = data[AI.lastUsedUserId].toOptLong()
            row.identityData = data[AI.identityData].toJsonMapOrEmpty().toMutableMap()
            row.updatedAt = data[PF.updatedAt].toOptInstant()
            row.data = data.toMap()
            return row
        }

        /** The stored map for a brand-new identity at [primaryId], verified at [verifiedAt] when the address is proven. */
        fun mkInitialIdentity(identityId: String, primaryId: String, verifiedAt: Instant?): Map<String, Any?> = buildMap {
            put(AI.identityId, identityId)
            put(AI.primaryId, primaryId)
            if (verifiedAt != null) put(AI.verifiedAt, verifiedAt)
            put(AI.identityData, mutableMapOf<String, Any?>())
        }
    }
}
