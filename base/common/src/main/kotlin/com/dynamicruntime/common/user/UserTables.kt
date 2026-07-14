package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.tableModule

/** The SQL topic the auth tables belong to. */
const val authTopic = "auth"

/** Auth table names. Each name matches its value. */
@Suppress("ConstPropertyName")
object UT {
    const val authUsers = "AuthUsers"
    const val authUserDevices = "AuthUserDevices"
}

/** `AuthUsers` column names. */
@Suppress("ConstPropertyName")
object AU {
    /** Numeric id of the user (the table's auto-incrementing counter and primary key). */
    const val userId = "userId"

    /** Primary identifier for the user -- in the default implementation, the primary email address. */
    const val primaryId = "primaryId"

    /** The user's unique preferred display/login name. */
    const val username = "username"

    /** Auth data map: roles, the (optional) encoded password, and contacts. Keys are [AD]. */
    const val authUserData = "authUserData"
}

/** Keys within the [AU.authUserData] map. */
@Suppress("ConstPropertyName")
object AD {
    /** List of granted role names. */
    const val roles = "roles"

    /** The encoded password, or absent when the user has not opted into a password (login is by code). */
    const val encodedPassword = "encodedPassword"

    /** List of contact descriptors (each a map with an address/type). */
    const val contacts = "contacts"

    /** List of contact addresses that have been verified. */
    const val validatedContacts = "validatedContacts"
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
 * `AuthUsers` is keyed by an auto-incrementing `userId`, with unique indexes on `primaryId` and `username`
 * (dn's transaction-lock columns are omitted: the verify-code flows use plain sessions, not topic
 * transactions -- optimistic/locked updates can be added with the password work). `AuthUserDevices` records
 * the devices a user logs in from (dn's
 * `AuthLoginSources`, renamed). DN's `AuthContacts` is omitted (unused there -- contacts live in
 * `authUserData`), as are `AuthTokens` (batch/test only) and `UserProfiles` (stubbed: a different approach is
 * coming).
 */
fun authTables(cxt: KdrCxt): List<KdrTable> = tableModule(cxt, namespace = "user", topic = authTopic) {
    table(UT.authUsers, "Main table for authenticating users.") {
        column(AU.userId, "Numeric id of the user.", required = true, autoIncrement = true) { type = SCT.integer }
        column(AU.primaryId, "Primary identifier (default: the primary email address).", required = true)
        column(AU.username, "The user's unique preferred name.", required = true)
        column(AU.authUserData, "Auth data: roles, optional encoded password, contacts.") { type = SCT.kObject }
        primaryKey(AU.userId)
        forAccount()
        index(AU.primaryId, unique = true)
        index(AU.username, unique = true)
    }
    table(UT.authUserDevices, "Devices from which a user's logins originate.") {
        column(AUD.deviceGuid, "Unique id attached to the requesting agent (a browser cookie).", required = true)
        column(AUD.deviceData, "Captured information about the device (IPs, user agents).") { type = SCT.kObject }
        column(AUD.deviceVerified, "Whether the device is verified as trusted.") { type = SCT.boolean }
        column(AUD.verifyExpiration, "When the device's verification expires.") { dateTime() }
        forUsers() // adds the owning userId + account columns
        primaryKey(AU.userId, AUD.deviceGuid)
        index(AUD.deviceGuid)
    }
}
