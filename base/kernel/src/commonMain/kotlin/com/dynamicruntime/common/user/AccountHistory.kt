package com.dynamicruntime.common.user

import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypesBuilder
import com.dynamicruntime.common.util.toOptInstantOrNull
import kotlin.time.Instant

/**
 * Field names of an [AccountHistory] (issue #1480). Each matches its value; the registration and the proof travel
 * under the names the admin console gives them (`USF.registered.at`, `ADF.verifiedAt`), so a date is called one thing
 * whichever surface shows it.
 */
@Suppress("ConstPropertyName")
object ACCT {
    const val registeredAt = "registeredAt"
    const val previousLoggedInAt = "previousLoggedInAt"
    const val verifiedAt = "verifiedAt"
}

/**
 * The signed-in user's own history (issue #1480), what Profile's account panel shows of it: when its person claimed it
 * ([registeredAt]), the sign-in before the one in use ([previousLoggedInAt]; a switch between users is none), and when
 * the person's address was proven ([verifiedAt], the identity's). Each is null while it has not happened. Only the
 * caller's own row and identity: nothing about the person's other users.
 *
 * Not the user's activation, which an invitation or a re-enable moves, so it reads out of order on a person's own page,
 * nor whether a password is set, which the profile config's features already say and the Sign-in panel shows.
 *
 * In the kernel so the page reads it by the names the backend writes, and its schema type is defined beside the
 * serialization ([defineInfoType]).
 */
data class AccountHistory(
    val registeredAt: Instant? = null,
    val previousLoggedInAt: Instant? = null,
    val verifiedAt: Instant? = null,
) {
    /** The history as it travels: a date that has not happened is left out. */
    fun toInfo(): Map<String, Any?> = buildMap {
        registeredAt?.let { put(ACCT.registeredAt, it) }
        previousLoggedInAt?.let { put(ACCT.previousLoggedInAt, it) }
        verifiedAt?.let { put(ACCT.verifiedAt, it) }
    }

    @Suppress("ConstPropertyName")
    companion object {
        /** Schema type name for [toInfo]. */
        const val infoTypeName = "AccountHistory"

        /** The mirror of [toInfo]: a date absent, or not one, is null. */
        fun fromInfo(info: Map<String, Any?>): AccountHistory = AccountHistory(
            registeredAt = info[ACCT.registeredAt].toOptInstantOrNull(),
            previousLoggedInAt = info[ACCT.previousLoggedInAt].toOptInstantOrNull(),
            verifiedAt = info[ACCT.verifiedAt].toOptInstantOrNull(),
        )

        /** Defines [infoTypeName] on [builder], kept beside the serialization. */
        fun defineInfoType(builder: SchTypesBuilder) {
            builder.type(infoTypeName) {
                type = SCT.kObject
                property(ACCT.registeredAt, "When the user's person claimed it; absent while nobody has.") { dateTime() }
                property(
                    ACCT.previousLoggedInAt,
                    "The sign-in before the one in use; absent until the second. A switch between users is not one.",
                ) { dateTime() }
                property(
                    ACCT.verifiedAt,
                    "When the person's address was proven (a code, an invitation link, or a Google sign-in); absent while unproven.",
                ) { dateTime() }
            }
        }
    }
}
