package com.dynamicruntime.common.user

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Instant

/**
 * A row of `AuthIdentities` (issue #747): the **person behind a login** -- keyed by a random [identityId] and
 * reachable by the one normalized address [primaryId] -- as distinct from the `AuthUsers` rows that person
 * acts *as*, each in one client with one persona. It owns everything about *proving who you are* (issue
 * #748): the address and when it was proven, the optional password, the contacts; a user owns only what you
 * may do and who you are within a client.
 *
 * The password is promoted out of the stored [identityData] map into [encodedPassword] and scrubbed from
 * [data], so it never rides downstream -- the arrangement `AuthUserRow` had before the credentials moved.
 * Mutable, like [AuthUserRow], and written back whole by `UserService.updateIdentity`; [data] is the stored
 * row for the write.
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

    /** The encoded password, or null when the person logs in by verification code only (optional passwords). */
    var encodedPassword: String? = null

    /** Whether the person has opted into a password -- what the profile and the admin console report. */
    val hasPassword: Boolean get() = encodedPassword != null

    /** The remaining identity-data map (the password promoted out): contacts, keyed by `IDD`. */
    var identityData: MutableMap<String, Any?> = mutableMapOf()

    /** Whether the identity has a recorded contact -- the precondition for assigning it a password. */
    val hasContact: Boolean get() = identityData.containsKey(IDD.contacts)

    /** When the row was last written; the version `updateIdentity` guards on. */
    var updatedAt: Instant? = null

    /** The raw stored row, for callers that mutate and write it back. */
    var data: Map<String, Any?> = emptyMap()

    /**
     * Records that the address was proven at [at] -- a code read from the inbox, or a Google-verified sign-in.
     * The first proof sets [verifiedAt]; every proof makes sure the address is among the validated contacts.
     * Returns whether anything changed, so a login that verifies nothing new writes nothing.
     */
    fun markVerified(at: Instant): Boolean {
        var changed = false
        if (verifiedAt == null) {
            verifiedAt = at
            changed = true
        }
        val validated = identityData[IDD.validatedContacts].toJsonListOfStrings()
        if (primaryId !in validated) {
            identityData[IDD.validatedContacts] = validated + primaryId
            changed = true
        }
        return changed
    }

    /**
     * Retires the identity behind a permanently deleted last user (`UserService.deleteUser`): the address
     * becomes the `deleted-<userId>` form -- freeing the real one for re-registration -- and the credentials
     * and contacts are dropped, so nothing about the person survives but the retired id its tombstones point at.
     */
    fun retire(deletedUserId: Long) {
        primaryId = AuthUserRow.deletedPrimaryId(deletedUserId)
        encodedPassword = null
        identityData = mutableMapOf()
    }

    /** Repackages the typed fields into a storage map (the password folded back into `identityData`). */
    fun toMap(): Map<String, Any?> {
        val out = data.toMutableMap()
        out[AI.identityId] = identityId
        out[AI.primaryId] = primaryId
        out[AI.verifiedAt] = verifiedAt
        out[AI.defaultUserId] = defaultUserId
        out[AI.lastUsedUserId] = lastUsedUserId
        val newIdentityData = identityData.toMutableMap()
        if (encodedPassword != null) newIdentityData[IDD.encodedPassword] = encodedPassword else newIdentityData.remove(IDD.encodedPassword)
        out[AI.identityData] = newIdentityData
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
            val identityData = data[AI.identityData].toJsonMapOrEmpty().toMutableMap()
            row.encodedPassword = identityData[IDD.encodedPassword].toOptStr()
            identityData.remove(IDD.encodedPassword) // never let the password leak downstream via `data`
            row.identityData = identityData
            row.updatedAt = data[PF.updatedAt].toOptInstant()
            // The retained `data` is a copy whose nested map is the scrubbed one above, so the promise that the
            // password never rides in `data` holds for the raw row too, and a cached raw row is never aliased.
            val retained = data.toMutableMap()
            retained[AI.identityData] = identityData
            row.data = retained
            return row
        }

        /**
         * The stored map for a brand-new identity at [primaryId], with the address recorded as its email
         * contact; [verifiedAt] set says the address is already proven, and lists it among the validated
         * contacts as [markVerified] would.
         */
        fun mkInitialIdentity(identityId: String, primaryId: String, verifiedAt: Instant?): Map<String, Any?> = buildMap {
            put(AI.identityId, identityId)
            put(AI.primaryId, primaryId)
            if (verifiedAt != null) put(AI.verifiedAt, verifiedAt)
            put(AI.identityData, mutableMapOf<String, Any?>(
                IDD.contacts to listOf(mapOf(AC2.address to primaryId, AC2.type to AC2.email)),
            ).also { if (verifiedAt != null) it[IDD.validatedContacts] = listOf(primaryId) })
        }

        /** The address in a stored identity map, read without extracting the row (what each user row's extraction reads). */
        fun addressIn(raw: Map<String, Any?>): String? = raw[AI.primaryId].toOptStr()

        /** Whether a stored identity map holds a password, read without extracting the row. */
        fun hasPasswordIn(raw: Map<String, Any?>): Boolean = raw[AI.identityData].toJsonMapOrEmpty()[IDD.encodedPassword] != null
    }
}
