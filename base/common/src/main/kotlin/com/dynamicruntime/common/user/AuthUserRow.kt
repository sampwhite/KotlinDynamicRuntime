package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypesBuilder
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.util.trimToNull
import kotlin.time.Instant

/**
 * A user's authentication row, extracted from the `AuthUsers` table into typed fields. Ported from dn's
 * `AuthUserRow`, kd2-simplified to a single [client] (no group/shard). The credentials are not here: the
 * password and contacts are the identity's (`AuthIdentityRow`, issue #748), and this row says only what the
 * user may do and who they are within a client. The class shape is deliberately independent of the storage
 * shape (fields are read/written explicitly).
 */
class AuthUserRow(
    val userId: Long,
    val client: String,
    /** The identity (person) this user is (issue #747); the address below is read through it. */
    val identityId: String,
    /**
     * The identity's address. **Derived, not stored on this row** since the split (issue #747): [extract]
     * resolves it through the identity, so every reader that ever showed or searched the email keeps working.
     */
    val primaryId: String,
    /**
     * Whether the identity has a password (issue #748). Derived through the identity like [primaryId], so the
     * profile and the admin console keep reporting it off the row they already hold; the password itself is
     * never here.
     */
    val hasPassword: Boolean = false,
) {
    /** The user's persona (issue #747), frozen at creation; see `PERSONA`. */
    var persona: String = PERSONA.member

    /** Distinguishes same-persona users of one identity in one client (issue #747); `""` for the ordinary one. */
    var personId: String = ""

    /** The user's primary organization within [client], or null when they have none (issue #225). */
    var org: String? = null

    /** Whether this account belongs to a business rather than a person (see [AD.isEntity]). */
    var isEntity: Boolean = false

    /**
     * The account's real-world (non-unique) name: a person's full name, or a business's name when [isEntity].
     * Null when unnamed. Survives a change of [isEntity] -- both kinds of account have one (see [AD.name]).
     *
     * **Normalized on assignment** ([normalizeName]): trimmed, and blank stored as no name. Four callers set
     * this -- registration, admin create, admin edit, and the profile page -- and each was applying that rule by
     * hand, which is three chances for them to disagree about whether `"  "` means "no name" or a name made of
     * spaces. Putting it in the setter makes it unbypassable rather than merely agreed.
     */
    var name: String? = null
        set(value) {
            field = normalizeName(value)
        }

    var enabled: Boolean = false

    /**
     * When this account was **permanently deleted** (its identity obfuscated), or null for a live one. Its
     * presence is the tombstone marker: [isDeleted] reads it, and every administrative edit refuses a row for
     * which it is set. Read from [AD.deletedAt] in the auth data.
     */
    var deletedAt: Instant? = null

    /** Whether this is a permanently-deleted tombstone -- obfuscated, disabled, and not editable. */
    val isDeleted: Boolean get() = deletedAt != null

    /**
     * When the row was last written (`updatedAt` protocol column), or null for a row never persisted. Surfaced
     * for the cache search (issue #411): its default sort key and a column the console can order on. Read from
     * [data], not from [authUserData] -- it is a real column, unlike [org] and [name].
     */
    var updatedAt: Instant? = null

    /**
     * When the person **proved this user is theirs** (issues #462, #749), or null for a user nobody has claimed
     * -- one an administrator provisioned for somebody else. Set by a verification code used for this user, by
     * the test fixture, or by the person creating the user for themself; cleared when a recoverably deleted
     * user is provisioned again. Read from [AD.registeredAt] in the auth data, like the three below.
     */
    var registeredAt: Instant? = null

    /**
     * Whether the person has claimed this user. Only a registered user is offered as a login target or in the
     * switcher (`UserService.defaultUserOf`, `userChoicesFor`): an unclaimed one is not yet the person's to
     * act as.
     */
    val isRegistered: Boolean get() = registeredAt != null

    /** When the account most recently became active -- at creation, and on each re-enable. */
    var activatedAt: Instant? = null

    /** When a login sequence last completed. A presented session cookie is not a login and does not move it. */
    var lastLoggedInAt: Instant? = null

    /**
     * When the account was last edited: anything that is not a login and not an activation.
     *
     * Against [updatedAt], which is the storage-level "this row was written" and therefore moves on a login
     * too. This is the one an administrator is asking about, and is what the console shows.
     */
    var lastEditedAt: Instant? = null

    lateinit var username: String
    var roles: List<String> = listOf(ROLE.user)

    /** The remaining auth-data map (the typed fields promoted out). */
    var authUserData: MutableMap<String, Any?> = mutableMapOf()

    /** The raw stored row, for callers that mutate and write it back. */
    var data: Map<String, Any?> = emptyMap()

    /** A placeholder username (`@<primaryId>`) means the user has not chosen a real username yet. */
    val needsRealUsername: Boolean get() = username.startsWith(usernameTmpPrefix)

    /** The name to show others: the chosen username, or the primaryId while still a placeholder. */
    fun publicName(): String = if (needsRealUsername) primaryId else username

    /**
     * Whether this row matches an admin-console [lowerTerm] (already lower-cased by the caller): a
     * case-insensitive substring of the email, the username, or the account's [name] (a person's full name or
     * a business's). Lives here so the searchable fields stay listed beside the fields themselves;
     * `UserService.listUsers` applies it after extraction, since [name] is not an SQL column.
     */
    fun matchesSearch(lowerTerm: String): Boolean =
        primaryId.lowercase().contains(lowerTerm) ||
            username.lowercase().contains(lowerTerm) ||
            (name?.lowercase()?.contains(lowerTerm) == true)

    /**
     * Loads a [UserProfile] from this row -- the acting/display identity for a logged-in user. A convenience
     * for now; a future variant may load only part of the profile for high-volume paths.
     */
    fun toUserProfile(): UserProfile = UserProfile(
        authId = userId.toString(), userId = userId, client = client, org = org, roles = roles.toSet(),
        publicName = publicName(), hasPassword = hasPassword,
        isEntity = isEntity, name = name, identityId = identityId, persona = persona,
    )

    /**
     * The admin console's view of this user ([ADTY.adminUser], defined by [defineAdminType]): identity, roles,
     * and account state. Deliberately *not* [toUserProfile] -- that is the acting identity of the caller, while
     * this describes some other user being administered, and the two must be free to diverge. Only whether
     * the identity has a password is reported, never the password.
     */
    fun toAdminInfo(): Map<String, Any?> = mapOf(
        ADF.userId to userId,
        ADF.primaryId to primaryId,
        ADF.username to username,
        ADF.client to client,
        ADF.org to org,
        ADF.isEntity to isEntity,
        ADF.name to name,
        ADF.roles to roles,
        ADF.enabled to enabled,
        ADF.hasPassword to hasPassword,
        ADF.deleted to isDeleted,
        ADF.deletedAt to deletedAt,
        ADF.updatedAt to updatedAt,
        // Keyed off the date registry rather than a second set of constants holding the same strings
        // (issue #462). `ADF.updatedAt` predates that and stays as it is.
        USF.registered.at to registeredAt,
        USF.activated.at to activatedAt,
        USF.lastLoggedIn.at to lastLoggedInAt,
        USF.lastEdited.at to lastEditedAt,
    )

    /** Writes [value] under [key] when it is set, and removes the key when it is not. */
    private fun putDate(data: MutableMap<String, Any?>, key: String, value: Instant?) {
        if (value != null) data[key] = value else data.remove(key)
    }

    /** Repackages the typed fields into a storage map (roles folded back into `authUserData`). */
    fun toMap(): Map<String, Any?> {
        val newAuthData = authUserData.toMutableMap()
        newAuthData[AD.roles] = roles
        // Removed rather than written as null when absent: most users have no organization, and an explicit
        // null would be stored in every row's JSON for the sake of the few that do.
        if (org != null) newAuthData[AD.org] = org else newAuthData.remove(AD.org)
        // Entity data rides along the same way: written only when it is actually set, so a personal account's
        // JSON does not carry `isEntity: false` in every row. `name` is independent of the flag -- a personal
        // account has one too -- so clearing `isEntity` leaves the name alone.
        if (isEntity) newAuthData[AD.isEntity] = true else newAuthData.remove(AD.isEntity)
        if (name != null) newAuthData[AD.name] = name else newAuthData.remove(AD.name)
        // Written back explicitly rather than left to ride along in `authUserData`, so that setting the typed
        // field is what persists -- the rule the note at the end of this method states. Absent stays absent
        // for the same reason `org` does: an account that has never logged in should not carry a null saying
        // so in every row.
        putDate(newAuthData, AD.registeredAt, registeredAt)
        putDate(newAuthData, AD.activatedAt, activatedAt)
        putDate(newAuthData, AD.lastLoggedInAt, lastLoggedInAt)
        putDate(newAuthData, AD.lastEditedAt, lastEditedAt)
        val retData = data.toMutableMap()
        retData[AU.identityId] = identityId
        retData[AU.persona] = persona
        retData[AU.personId] = personId
        retData[AU.username] = username
        retData[AU.authUserData] = newAuthData
        // Every typed field this class exposes has to travel back out, or a caller that sets one sees it
        // silently dropped on write. `enabled` is the one that bites: see UserService.updateUser, which has to
        // defend it a second time from the standard column stamping.
        retData[PF.enabled] = enabled
        return retData
    }

    @Suppress("ConstPropertyName")
    companion object {
        /** Prefix marking a not-yet-chosen (placeholder) username. */
        const val usernameTmpPrefix = "@"

        /**
         * The domain a permanently deleted user's obfuscated email is moved to. `.invalid` is reserved by
         * RFC 2606 and can never resolve, so the address is unmistakably dead; the `deleted-<userId>` local
         * part ([deletedPrimaryId]) is the *indication* that this row was once a real user, and keeps the
         * value unique so the DB's unique index on `primaryId` is never violated by two deletions.
         */
        const val deletedIdDomain = "deleted.invalid"

        /** The obfuscated email a permanently deleted [userId] is given: `deleted-<userId>@deleted.invalid`. */
        fun deletedPrimaryId(userId: Long): String = "deleted-$userId@$deletedIdDomain"

        /** The obfuscated username a permanently deleted [userId] is given: `deleted-<userId>` (unique). */
        fun deletedUsername(userId: Long): String = "deleted-$userId"

        /** The personId a tombstone takes, `deleted-<userId>`, so its (identity, client, persona, personId) key is freed. */
        fun deletedPersonId(userId: Long): String = "deleted-$userId"

        /**
         * A **permanently deleted tombstone** of [original]: non-recoverable by construction, but a *retirement*
         * rather than a privacy erasure. It obfuscates the **login and contact** identity while keeping the
         * **descriptive** fields a human debugger needs to recognize the account later.
         *
         * Obfuscated or cleared: the username becomes the `deleted-<userId>` form above (which frees the
         * original for re-registration and marks the row as a former user), the key is given up, and the
         * roles are cleared, so the row never reads as an administrator. `enabled` is false, which by itself
         * already denies login and empties the user's live roles (`AuthUserUtil.refreshActingRoles`); the
         * obfuscation is what makes it *permanent*. The address, password and contacts are the identity's
         * (issue #748); `UserService.deleteUser` retires the identity when this was its last live user.
         *
         * **Kept** (issue: revisit clearing): the display [name], [org] and [isEntity]. These identify the
         * *account* to somebody investigating "did this user own anything?", where the join key is the
         * surviving [userId] but the name is what makes a tombstone recognizable. This means the delete is
         * **not** a right-to-be-forgotten erasure -- the name (PII) survives; a deployment that needs true
         * erasure would clear these too.
         *
         * It carries [original]'s stored `data` (so `updateUser`'s optimistic-concurrency guard still fires on
         * the version it was read at). Its derived address is the retired form the identity takes when this
         * was its last user, so the row returned to the deleting administrator reads as the tombstone it is.
         */
        fun deletedTombstone(original: AuthUserRow, deletedAt: Instant, deletedBy: Long): AuthUserRow {
            val row = AuthUserRow(original.userId, original.client, original.identityId, deletedPrimaryId(original.userId))
            row.persona = original.persona
            // The tombstone gives up the key (issue #747), as it gives up the username: with the original
            // personId kept, the person could never again hold an ordinary user in this client.
            row.personId = deletedPersonId(original.userId)
            row.username = deletedUsername(original.userId)
            row.enabled = false
            // Roles are dropped too, not merely made inert by the disable: a tombstone must not read as an
            // administrator, in the console or anywhere the row is loaded.
            row.roles = emptyList()
            row.org = original.org
            row.isEntity = original.isEntity
            // Kept for debugging/recognition: this is a retirement, not a privacy erasure (see the doc).
            row.name = original.name
            row.deletedAt = deletedAt
            // Stamp the deletion marker + its audit. Keep whatever else was held.
            row.authUserData = original.authUserData.toMutableMap().also {
                it[AD.deletedAt] = deletedAt
                it[AD.deletedBy] = deletedBy
            }
            // Keep the raw row for its version stamp.
            row.data = original.data
            return row
        }

        /**
         * The one rule for a display name: trimmed, and blank is no name at all. Applied by [name]'s setter,
         * and exposed for the one caller that has no row to assign to -- admin *create*, which assembles a raw
         * `authUserData` map before the row exists.
         */
        fun normalizeName(value: String?): String? = value.trimToNull()

        /**
         * Defines the [ADTY.adminUser] schema type -- the shape of [toAdminInfo] -- on [builder]. Kept beside
         * the serialization it describes (the co-location rule), so a field added to one is visibly missing
         * from the other.
         */
        fun defineAdminType(builder: SchTypesBuilder) {
            builder.type(ADTY.adminUser) {
                type = SCT.kObject
                property(ADF.userId, "The user's numeric id.", required = true) { type = SCT.integer }
                property(ADF.primaryId, "Primary identifier (the primary email address).", required = true)
                property(ADF.username, "The user's unique preferred name.", required = true)
                property(ADF.roles, "The roles granted to the user.", required = true) {
                    type = SCT.array
                    items { type = SCT.string }
                }
                property(ADF.client, "The client the user belongs to.", required = true)
                property(ADF.org, "The user's primary organization within their client, when they have one.")
                property(ADF.isEntity, "Whether this account belongs to a business rather than a person.") { type = SCT.boolean }
                property(ADF.name, "The account's real-world name: a person's full name, or a business's name.")
                property(ADF.enabled, "Whether the account is active.", required = true) { type = SCT.boolean }
                property(ADF.hasPassword, "Whether the user has opted into a password.", required = true) {
                    type = SCT.boolean
                }
                property(ADF.deleted, "Whether the account was permanently deleted (an obfuscated tombstone).", required = true) {
                    type = SCT.boolean
                }
                property(ADF.deletedAt, "When the account was permanently deleted, for a deleted account.") { dateTime() }
                property(ADF.updatedAt, "When the row was last updated -- moved by anything, a login included.") { dateTime() }
                // The four tracked dates (issue #462). Declared from the same registry the sort keys and the
                // filter ranges come from, so a date is one entry rather than four places that must agree.
                property(USF.registered.at, "When the account was first created; never overwritten.") { dateTime() }
                property(USF.activated.at, "When the account most recently became active -- at creation, and on each re-enable.") { dateTime() }
                property(USF.lastLoggedIn.at, "When a login sequence last completed; a refreshed cookie does not count.") { dateTime() }
                property(USF.lastEdited.at, "When the account was last edited -- not a login, not an activation.") { dateTime() }
            }
        }

        /**
         * Builds a typed row from a stored `AuthUsers` map. [identityOf] resolves the row's identity to its
         * **stored** `AuthIdentities` map (issue #747) -- `UserService` hands in a lookup through the identity
         * cache; a test hands in a stub -- from which the two derived facts, the address and whether there is a
         * password, are read without extracting the identity (`AuthIdentityRow.addressIn` / `hasPasswordIn`):
         * a listing extracts a page of users and should pay a map hit each, not an extraction. An identity
         * that cannot be resolved is a broken invariant (a user row always points at an existing identity) and
         * fails as such.
         */
        fun extract(data: Map<String, Any?>, identityOf: (String) -> Map<String, Any?>?): AuthUserRow {
            val userId = data[AU.userId].toOptLong() ?: throw KdrException("AuthUsers row is missing its userId.")
            val client = data[PF.client].toOptStr() ?: ""
            val identityId = data[AU.identityId].toOptStr()
                ?: throw KdrException("AuthUsers row $userId is missing its identityId.")
            val identity = identityOf(identityId)
                ?: throw KdrException("AuthUsers row $userId points at identity '$identityId', which is not present.")
            val primaryId = AuthIdentityRow.addressIn(identity)
                ?: throw KdrException("AuthUsers row $userId points at identity '$identityId', which has no address.")
            val row = AuthUserRow(userId, client, identityId, primaryId, AuthIdentityRow.hasPasswordIn(identity))
            row.persona = data[AU.persona].toOptStr() ?: PERSONA.member
            row.personId = data[AU.personId].toOptStr() ?: ""
            row.enabled = data[PF.enabled] == true
            row.username = data[AU.username].toOptStr() ?: (usernameTmpPrefix + primaryId)
            val userData = (data[AU.authUserData]?.toJsonMap() ?: emptyMap()).toMutableMap()
            row.roles = (userData[AD.roles] as? List<*>)?.mapNotNull { it?.toString() } ?: listOf(ROLE.user)
            row.org = userData[AD.org].toOptStr()?.ifEmpty { null }
            row.isEntity = userData[AD.isEntity] == true
            row.name = userData[AD.name].toOptStr()
            row.deletedAt = userData[AD.deletedAt].toOptInstant()
            row.registeredAt = userData[AD.registeredAt].toOptInstant()
            row.activatedAt = userData[AD.activatedAt].toOptInstant()
            row.lastLoggedInAt = userData[AD.lastLoggedInAt].toOptInstant()
            row.lastEditedAt = userData[AD.lastEditedAt].toOptInstant()
            // A real column, read from the row rather than the auth-data blob (unlike org/name/deletedAt).
            row.updatedAt = data[PF.updatedAt].toOptInstant()
            row.authUserData = userData
            // The retained `data` is a copy whose nested auth map is the mutable one above, so the row never
            // aliases the caller's map and a cached raw row cannot be reached through a row handed out to
            // request code.
            val retained = data.toMutableMap()
            retained[AU.authUserData] = userData
            row.data = retained
            return row
        }

        /**
         * The initially provisioned row for a user of the identity [identityId] at address [primaryId] (the
         * address only seeds the placeholder username; it is not stored here since issue #747), in [client]
         * with [persona] and [personId]. Callers go through `UserService.provisionUser`, which creates or finds
         * the identity first.
         */
        fun mkInitialUser(
            identityId: String,
            primaryId: String,
            client: String,
            roles: List<String>,
            org: String? = null,
            persona: String = PERSONA.member,
            personId: String = "",
            /**
             * When the account came into being (issue #462), stamped as [AD.activatedAt] -- and as
             * [AD.registeredAt] when [registered]. Passed in rather than read here because this builds a map
             * and has no context to ask for the time; null leaves both unset.
             */
            createdAt: Instant? = null,
            /** Whether the person has claimed this user at creation (issue #749): a code proved it, the fixture
             * made it, or they made it for themself. */
            registered: Boolean = false,
        ): Map<String, Any?> = mapOf(
            AU.identityId to identityId,
            AU.persona to persona,
            AU.personId to personId,
            AU.username to (usernameTmpPrefix + primaryId),
            PF.client to client,
            AU.authUserData to mutableMapOf<String, Any?>(AD.roles to roles).also {
                if (org != null) it[AD.org] = org
                // Creation is the first activation. Registration is stamped from the same moment when the
                // person is behind the creation; otherwise it waits for them to prove the user (issue #749).
                if (createdAt != null) {
                    it[AD.activatedAt] = createdAt
                    if (registered) it[AD.registeredAt] = createdAt
                }
            },
        )
    }
}
