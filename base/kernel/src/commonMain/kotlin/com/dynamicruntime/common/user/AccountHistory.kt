package com.dynamicruntime.common.user

import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypesBuilder
import com.dynamicruntime.common.util.toOptInstantOrNull
import kotlin.time.Instant

/**
 * Field names of an [AccountHistory] (issue #1480). Each matches its value, and the user's dates are those the admin
 * console reads a user by (`USF.registered.at`, ...) and the identity's proof the name its person view gives it
 * (`ADF.verifiedAt`), so a date travels under one name whichever surface shows it.
 */
@Suppress("ConstPropertyName")
object ACCT {
    const val registeredAt = "registeredAt"
    const val activatedAt = "activatedAt"
    const val lastLoggedInAt = "lastLoggedInAt"
    const val verifiedAt = "verifiedAt"
}

/**
 * The signed-in user's own history (issue #1480), what Profile's account panel shows of it: when the user was
 * [registeredAt] (its person claimed it) and [activatedAt] (it came into being, or was recovered), its [lastLoggedInAt],
 * and when the person's address was proven ([verifiedAt], the identity's). Each is null while it has not happened. Only
 * the caller's own row and identity: nothing about the person's other users.
 *
 * In the kernel so the page reads it by the names the backend writes, and its schema type is defined beside the
 * serialization ([defineInfoType]).
 */
data class AccountHistory(
    val registeredAt: Instant? = null,
    val activatedAt: Instant? = null,
    val lastLoggedInAt: Instant? = null,
    val verifiedAt: Instant? = null,
) {
    /** The history as it travels: a date that has not happened is left out. */
    fun toInfo(): Map<String, Any?> = buildMap {
        registeredAt?.let { put(ACCT.registeredAt, it) }
        activatedAt?.let { put(ACCT.activatedAt, it) }
        lastLoggedInAt?.let { put(ACCT.lastLoggedInAt, it) }
        verifiedAt?.let { put(ACCT.verifiedAt, it) }
    }

    @Suppress("ConstPropertyName")
    companion object {
        /** Schema type name for [toInfo]. */
        const val infoTypeName = "AccountHistory"

        /** The mirror of [toInfo]: a date absent, or not one, is null. */
        fun fromInfo(info: Map<String, Any?>): AccountHistory = AccountHistory(
            registeredAt = info[ACCT.registeredAt].toOptInstantOrNull(),
            activatedAt = info[ACCT.activatedAt].toOptInstantOrNull(),
            lastLoggedInAt = info[ACCT.lastLoggedInAt].toOptInstantOrNull(),
            verifiedAt = info[ACCT.verifiedAt].toOptInstantOrNull(),
        )

        /** Defines [infoTypeName] on [builder], kept beside the serialization. */
        fun defineInfoType(builder: SchTypesBuilder) {
            builder.type(infoTypeName) {
                type = SCT.kObject
                property(ACCT.registeredAt, "When the user's person claimed it; absent while nobody has.") { dateTime() }
                property(ACCT.activatedAt, "When the user came into being, or was last recovered.") { dateTime() }
                property(ACCT.lastLoggedInAt, "When the user last signed in.") { dateTime() }
                property(ACCT.verifiedAt, "When the person's address was proven (a code or an invitation link); absent while unproven.") {
                    dateTime()
                }
            }
        }
    }
}
