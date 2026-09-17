package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.tableModule

/** The SQL topic the auth tables belong to. */
const val authTopic = "auth"

/** Auth table names. Each name matches its value. */
@Suppress("ConstPropertyName")
object UT {
    /** The person behind a login (issue #747): the address, its verification, and which user it acts as. */
    const val authIdentities = "AuthIdentities"
    const val authUsers = "AuthUsers"
    const val authUserDevices = "AuthUserDevices"
    const val linkedUsers = "LinkedUsers"
}

/**
 * `LinkedUsers` column names (issue #157). An external identity provider's own key for a person, mapped to
 * the local [AU.userId] it signs in as.
 */
@Suppress("ConstPropertyName")
object LU {
    /** The external identity source, e.g. [LSRC.google]. Half of the primary key. */
    const val linkSource = "linkSource"

    /**
     * The source's **own** primary key for the identity -- for Google, the `sub` claim. The other half of the
     * primary key. Deliberately not the email: a provider's email can change or be reassigned, while this is
     * stable for the life of the account.
     */
    const val linkId = "linkId"

    /** Claims captured from the source when the link was made (its email at the time, display name, …). */
    const val linkData = "linkData"
}

/** Identity-source names for [LU.linkSource]. Each name matches its value. */
@Suppress("ConstPropertyName")
object LSRC {
    const val google = "google"
}

/** `AuthIdentities` column names (issue #747). */
@Suppress("ConstPropertyName")
object AI {
    /** The identity's random id (the primary key) -- what a user row points at, and what the cookie carries. */
    const val identityId = "identityId"

    /** The identity's one normalized email address (unique). Was `AuthUsers.primaryId` before the split. */
    const val primaryId = "primaryId"

    /** When the address was proven (a code read from the inbox); absent while nobody has. */
    const val verifiedAt = "verifiedAt"

    /** The user this identity logs in as by choice; absent for the fallback. */
    const val defaultUserId = "defaultUserId"

    /** The user this identity most recently acted as. */
    const val lastUsedUserId = "lastUsedUserId"

    /** The identity's own data blob (phase B: password, contacts). */
    const val identityData = "identityData"
}

/** `AuthUsers` column names. */
@Suppress("ConstPropertyName")
object AU {
    /** Numeric id of the user (the table's auto-incrementing counter and primary key). */
    const val userId = "userId"

    /** The [AI.identityId] of the person this user is (issue #747); the address is read through it. */
    const val identityId = "identityId"

    /**
     * What relationship this user has to the application (issue #747): `user`, `admin`, later `reviewer` or
     * `advisor`. Frozen at creation -- a different persona is a different user. Part of the unique key with
     * [identityId], the client and [personId].
     */
    const val persona = "persona"

    /**
     * Distinguishes several users of one identity with the same persona in the same client -- a batch of
     * near-identical UAT users, `1`, `2`, … or `A`, `B`, … -- and is `""` for the ordinary single one. Stored
     * as the empty string rather than null on purpose: nulls do not collide in a unique index, and the whole
     * point of the key is that two default users cannot.
     */
    const val personId = "personId"

    /** The user's unique preferred display/login name. */
    const val username = "username"

    /**
     * Auth data map: roles, identity (org, name, isEntity), the optional encoded password, contacts, and
     * lifecycle markers (e.g., deletion). The authoritative key list is [AD] -- this summary names the shape,
     * not every key.
     */
    const val authUserData = "authUserData"
}

/** Keys within the [AU.authUserData] map. */
@Suppress("ConstPropertyName")
object AD {
    /** List of granted role names. */
    const val roles = "roles"

    /**
     * The user's primary organization within their client, or absent when they have none (issue #225).
     *
     * Here rather than in a column on purpose: it rides with the identity (`UserProfile`, and the session
     * cookie) so a write can stamp it onto content without a lookup. The cost is that it cannot be a SQL
     * predicate, so narrowing a *user* list to an organization happens after the query -- content, which does
     * get a column, filters in SQL.
     */
    const val org = "org"

    /**
     * The four tracked dates (issue #462), stored in the auth-data payload beside [org].
     *
     * Their own literals rather than references to the wire keys they happen to match. A storage key and a
     * wire key are different contracts: renaming what an endpoint calls a field should not silently change
     * where it is *stored* and orphan every existing row. `org` and `ADF.org` are already separate for the
     * same reason.
     */
    const val registeredAt = "registeredAt"
    const val activatedAt = "activatedAt"
    const val lastLoggedInAt = "lastLoggedInAt"
    const val lastEditedAt = "lastEditedAt"

    /**
     * Whether this account belongs to a **business** rather than a person. It says how to read [name] -- a
     * business's name rather than a person's -- and is absent (i.e., false) for an ordinary personal account.
     * Set at registration, and held here beside the other identity data rather than in a column, the same way
     * [org] is.
     */
    const val isEntity = "isEntity"

    /**
     * The account's real-world name: the person's full name, or the business's name when [isEntity] is true.
     * **Not unique** -- two people or two businesses may share one -- so it is display copy, never an
     * identifier; the account is still keyed by its primary id and username. Absent when unnamed.
     *
     * One field for both cases on purpose: a personal account previously had nowhere to put a full name, so it
     * was displayed by its login identifier. [isEntity] chooses how to *label* and interpret this, not which
     * field to read.
     */
    const val name = "name"

    /** The encoded password, or absent when the user has not opted into a password (login is by code). */
    const val encodedPassword = "encodedPassword"

    /** List of contact descriptors (each a map with an address/type). */
    const val contacts = "contacts"

    /** List of contact addresses that have been verified. */
    const val validatedContacts = "validatedContacts"

    /** When the account was permanently deleted (its identity obfuscated); absent for a live account. Its
     *  presence is what marks a row as a tombstone. */
    const val deletedAt = "deletedAt"

    /** The userId of the administrator who permanently deleted the account -- the audit half of [deletedAt]. */
    const val deletedBy = "deletedBy"
}

/** `AuthUserDevices` column names (dn's `AuthLoginSources`, renamed to Device terminology). */
@Suppress("ConstPropertyName")
object AUD {
    /** Unique id attached to the requesting agent; for browsers it is a cookie set on the device. */
    const val deviceGuid = "deviceGuid"

    /** Captured information about the device (e.g. `capturedIps` -> user agents). */
    const val deviceData = "deviceData"

    /** Whether the device has been verified as trusted. */
    const val deviceVerified = "deviceVerified"

    /** When the device's verification expires. */
    const val verifyExpiration = "verifyExpiration"
}

/**
 * The auth topic's tables (issue #67), contributed to the schema store by the `common` component.
 *
 * `AuthIdentities` (issue #747) is the person behind a login, keyed by a random `identityId` with a unique
 * index on the normalized address; it deliberately has **no client column**, since one identity's users may
 * sit in several clients. `AuthUsers` is keyed by an auto-incrementing `userId`; each row points at its
 * identity, and `(identityId, client, persona, personId)` is unique -- the key the design settled on, held by
 * the database rather than by code -- while `username` keeps its own unique index. (dn's transaction-lock
 * columns are omitted: the verify-code flows use plain sessions, not topic transactions.) `AuthUserDevices`
 * records the devices a user logs in from (dn's
 * `AuthLoginSources`, renamed). `LinkedUsers` (issue #157) maps an external identity provider's own key for a
 * person onto a local `userId`. DN's `AuthContacts` is omitted (unused there -- contacts live in
 * `authUserData`), as are `AuthTokens` (batch/test only) and `UserProfiles` (stubbed: a different approach is
 * coming).
 */
fun authTables(cxt: KdrCxt): List<KdrTable> = tableModule(cxt, namespace = "user", topic = authTopic) {
    table(UT.authIdentities, "The person behind a login: one address, and the users it may act as.") {
        column(AI.identityId, "Random id of the identity.", required = true)
        column(AI.primaryId, "The identity's normalized email address.", required = true)
        column(AI.verifiedAt, "When the address was proven; absent while nobody has.") { dateTime() }
        column(AI.defaultUserId, "The user this identity logs in as by choice.") { type = SCT.integer }
        column(AI.lastUsedUserId, "The user this identity most recently acted as.") { type = SCT.integer }
        column(AI.identityData, "The identity's own data (later: password, contacts).") { type = SCT.kObject }
        primaryKey(AI.identityId)
        // No forClient(): an identity spans clients by design (issue #747).
        index(AI.primaryId, unique = true)
        index(PF.updatedAt)
    }
    table(UT.authUsers, "Main table for authenticating users.") {
        column(AU.userId, "Numeric id of the user.", required = true, autoIncrement = true) { type = SCT.integer }
        column(AU.identityId, "The identity (person) this user is.", required = true)
        column(AU.persona, "The user's persona, frozen at creation.", required = true)
        column(AU.personId, "Distinguishes same-persona users of one identity in one client; empty for the ordinary one.", required = true)
        column(AU.username, "The user's unique preferred name.", required = true)
        column(AU.authUserData, "Auth data: roles, identity (org, name), optional encoded password, contacts, and deletion markers.") { type = SCT.kObject }
        primaryKey(AU.userId)
        forClient()
        // The design's key (issue #747), enforced here rather than by a query-then-insert: one user per
        // identity, client, persona and personId. `personId` is "" for the ordinary user, never null, so the
        // index catches a second ordinary user too.
        index(AU.identityId, PF.client, AU.persona, AU.personId, unique = true)
        index(AU.username, unique = true)
        // The in-memory cache ([AuthUserCache]) reloads by asking for the rows changed since it last looked,
        // which is a predicate on `updatedAt` run every few seconds on every node. Without this index that is
        // a full scan of the one table guaranteed to grow.
        index(PF.updatedAt)
    }
    table(UT.linkedUsers, "External identities (Google, …) linked to a local user.") {
        column(LU.linkSource, "The external identity source (e.g. 'google').", required = true)
        column(LU.linkId, "The source's own primary key for the identity (for Google, the 'sub' claim).", required = true)
        column(LU.linkData, "Claims captured from the source when the link was made.") { type = SCT.kObject }
        forUsers() // adds the linked-to userId + client columns
        // The source plus that source's key is the identity, so it is the primary key -- one external identity
        // can only ever point at one local user, enforced by the database rather than by a query-then-insert.
        primaryKey(LU.linkSource, LU.linkId)
        // The reverse direction: every external identity linked to one user (a profile page listing them, and
        // unlinking). Not unique -- a user may link several sources, and several identities within one source.
        index(AU.userId)
    }
    table(UT.authUserDevices, "Devices from which a user's logins originate.") {
        column(AUD.deviceGuid, "Unique id attached to the requesting agent (a browser cookie).", required = true)
        column(AUD.deviceData, "Captured information about the device (IPs, user agents).") { type = SCT.kObject }
        column(AUD.deviceVerified, "Whether the device is verified as trusted.") { type = SCT.boolean }
        column(AUD.verifyExpiration, "When the device's verification expires.") { dateTime() }
        forUsers() // adds the owning userId + client columns
        primaryKey(AU.userId, AUD.deviceGuid)
        index(AUD.deviceGuid)
    }
}
