package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.KdrMsg
import com.dynamicruntime.common.mail.MailService
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.sql.KdrColumn
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlCxt
import com.dynamicruntime.common.sql.SqlScopeUtil
import com.dynamicruntime.common.sql.SqlStatement
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.sql.cache.SqlCacheRow
import com.dynamicruntime.common.sql.cache.SqlTableCache
import com.dynamicruntime.common.sql.cache.SqlTableCacheService
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.mkUniqueId
import com.dynamicruntime.common.util.normalizeLoginId
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.util.toT
import kotlin.time.Instant

/**
 * The user/auth service (issue #67): owns the [AuthFormHandler] and the SQL access to the `AuthIdentities`,
 * `AuthUsers`, `LinkedUsers` and `AuthUserDevices` tables. Ported from dn's `UserService` + `AuthQueryHolder`,
 * using kd2's topic/table SQL layer. Registered by the `common` component; found via [get].
 */
class UserService : ServiceInitializer {
    override val serviceName: String = UserService.serviceName

    lateinit var authFormHandler: AuthFormHandler

    /**
     * The in-memory `AuthUsers` cache, or null when the table-cache service is absent (see [AuthUserCache]).
     * Every single-row lookup below consults it first and falls back to SQL on a miss, so its absence costs
     * queries and nothing else. It holds the **raw row maps** -- see [AuthUserCache] for why.
     */
    var userCache: SqlTableCache<Map<String, Any?>>? = null

    /** The in-memory `AuthIdentities` cache (issue #747), or null without the cache service; see [AuthIdentityCache]. */
    var identityCache: SqlTableCache<Map<String, Any?>>? = null

    override fun checkInit(cxt: KdrCxt) {
        if (::authFormHandler.isInitialized) return
        val node = NodeService.get(cxt)
        val mail = MailService.get(cxt)
        // Null when the deployment configured no Google client id, which is what disables Google sign-in.
        val googleVerifier = GoogleAuthConfig.mkVerifier(cxt.instanceConfig)
        authFormHandler = AuthFormHandler(this, node, mail, googleVerifier)
        // Registered during this pass so the table-cache service's own checkReady -- which runs after every
        // service's checkInit -- finds it and performs the initial load at startup rather than in a request.
        identityCache = AuthIdentityCache.register(cxt)
        userCache = AuthUserCache.register(cxt)
    }

    // --- AuthIdentities (issue #747) ------------------------------------------

    private fun authIdentitiesTable(cxt: KdrCxt): KdrTable = cxt.getSchema().tables[UT.authIdentities]
        ?: throw KdrException("AuthIdentities table is not registered in the schema store.")

    /** The identity with [identityId], cache-first, or null. */
    fun queryIdentityById(cxt: KdrCxt, identityId: String): AuthIdentityRow? =
        cachedIdentity(cxt) { it.snapshot.get(it.idOf(identityId)) } ?: queryOneIdentity(cxt, AI.identityId, identityId)

    /** The identity at the (normalized) [address], cache-first, or null. */
    fun queryIdentityByAddress(cxt: KdrCxt, address: String): AuthIdentityRow? =
        cachedIdentity(cxt) { it.snapshot.byIndex(AI.primaryId, address) } ?: queryOneIdentity(cxt, AI.primaryId, address)

    /**
     * An identity's stored map, for extracting a user row -- the lookup [AuthUserRow.extract] takes to derive
     * the address and the password status. Hands out the cached raw map as it is (one refresh check per
     * resolver, not per row), so a listing that extracts a page of users pays a map hit each; a miss falls
     * back to SQL.
     */
    private fun identityOf(cxt: KdrCxt): (String) -> Map<String, Any?>? {
        val cache = identityCache?.also { it.checkRefresh(cxt) }
        return { id -> cache?.snapshot?.get(cache.idOf(id))?.value ?: queryOneIdentityRaw(cxt, AI.identityId, id) }
    }

    /**
     * The identity behind [user]. A user row always points at an existing identity -- extraction already
     * failed if it did not -- so a miss here is the same broken invariant, not an absence a caller handles.
     */
    fun identityOfUser(cxt: KdrCxt, user: AuthUserRow): AuthIdentityRow =
        queryIdentityById(cxt, user.identityId)
            ?: throw KdrException("User ${user.userId} points at identity '${user.identityId}', which is not present.")

    private inline fun cachedIdentity(
        cxt: KdrCxt,
        lookup: (SqlTableCache<Map<String, Any?>>) -> SqlCacheRow<Map<String, Any?>>?,
    ): AuthIdentityRow? {
        val cache = identityCache ?: return null
        cache.checkRefresh(cxt)
        val row = lookup(cache) ?: return null
        return AuthIdentityRow.extract(row.value)
    }

    private fun queryOneIdentity(cxt: KdrCxt, field: String, value: Any?): AuthIdentityRow? =
        queryOneIdentityRaw(cxt, field, value)?.let { AuthIdentityRow.extract(it) }

    private fun queryOneIdentityRaw(cxt: KdrCxt, field: String, value: Any?): Map<String, Any?>? {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = authIdentitiesTable(cxt)
        val stmt = SqlTopicUtil.mkNamedTableSelectStmt(sqlCxt, "qAuthIdentitiesBy_$field", table, listOf(field))
        var row: Map<String, Any?>? = null
        sqlCxt.sqlDb.withSession(cxt) {
            row = sqlCxt.sqlDb.queryOneStatement(cxt, stmt, mapOf(field to value))
        }
        return row
    }

    /**
     * The identity at [address], created if there is none. [verifiedAt] set says the address has just been
     * proven (a code read from the inbox, or a Google-verified sign-in); it is stamped on a new identity and
     * onto an existing one that had not been proven ([AuthIdentityRow.markVerified]).
     */
    fun getOrCreateIdentity(cxt: KdrCxt, address: String, verifiedAt: Instant? = null): AuthIdentityRow {
        queryIdentityByAddress(cxt, address)?.let { existing ->
            if (verifiedAt != null && existing.markVerified(verifiedAt)) {
                updateIdentity(cxt, existing)
            }
            return existing
        }
        val id = cxt.mkUniqueId()
        insertIdentity(cxt, AuthIdentityRow.mkInitialIdentity(id, address, verifiedAt))
        return queryIdentityById(cxt, id)
            ?: throw KdrException("Could not load the just-created identity for '$address'.", code = EXC.internalError)
    }

    fun insertIdentity(cxt: KdrCxt, data: Map<String, Any?>) {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = authIdentitiesTable(cxt)
        val stmt = SqlTopicUtil.mkTableInsertStmt(sqlCxt, table)
        val row = data.toMutableMap()
        SqlTopicUtil.prepForStdExecute(cxt, table, row)
        sqlCxt.sqlDb.withSession(cxt) { sqlCxt.sqlDb.executeStatement(cxt, stmt, row) }
    }

    /** Writes [row] back to its `AuthIdentities` record (by `identityId`), re-stamping protocol columns. */
    fun updateIdentity(cxt: KdrCxt, row: AuthIdentityRow) {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = authIdentitiesTable(cxt)
        val data = row.toMap().toMutableMap()
        SqlTopicUtil.prepForStdExecute(cxt, table, data)
        val stmt = SqlTopicUtil.mkTableUpdateStmt(sqlCxt, table)
        sqlCxt.sqlDb.withSession(cxt) { sqlCxt.sqlDb.executeStatement(cxt, stmt, data) }
        row.data = row.data.toMutableMap().also { it[PF.updatedAt] = data[PF.updatedAt] }
        row.updatedAt = data[PF.updatedAt].toOptInstant()
    }

    /**
     * Every user of the identity [identityId], lowest `userId` first, **disabled ones included** -- read from
     * SQL rather than the cache, which holds enabled rows only: the callers need the complete set (a disabled
     * user is recovered by provisioning its key again, and counts as live when deciding whether a permanent
     * delete retires the address), and none of them is on a hot path.
     */
    fun usersOfIdentity(cxt: KdrCxt, identityId: String): List<AuthUserRow> {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = authUsersTable(cxt)
        val stmt = SqlTopicUtil.mkNamedTableSelectStmt(sqlCxt, "qAuthUsersByIdentity", table, listOf(AU.identityId))
        var rows: List<Map<String, Any?>> = emptyList()
        sqlCxt.sqlDb.withSession(cxt) { rows = sqlCxt.sqlDb.queryStatement(cxt, stmt, mapOf(AU.identityId to identityId)) }
        return rows.map { AuthUserRow.extract(it, identityOf(cxt)) }.sortedBy { it.userId }
    }

    /**
     * The user an [identity] logs in as (issue #747): its chosen default, else the one it most recently acted
     * as, else its earliest -- whichever of those exists. **Among the registered, enabled users first**
     * (issues #748, #749): a disabled default falls through to the next enabled one, so disabling one of a
     * person's users does not lock the person out of the others, and a user nobody has claimed yet is never
     * the one a login lands on while a claimed one exists. Then the enabled ones -- a code login landing on an
     * unregistered user is what registers it, so it must be reachable when it is all there is -- and only when
     * none is enabled does the same rule pick a disabled one, so a login reports that account as inactive
     * rather than as unknown, and an administrative lookup by address still lands on a user.
     */
    fun defaultUserOf(cxt: KdrCxt, identity: AuthIdentityRow): AuthUserRow? {
        val users = usersOfIdentity(cxt, identity.identityId)
        val enabled = users.filter { it.enabled }
        val registered = enabled.filter { it.isRegistered }
        return pickDefault(identity, registered.ifEmpty { enabled }.ifEmpty { users })
    }

    /** [defaultUserOf] confined to the registered, enabled users, or null when the identity has none (issue #749). */
    fun registeredDefaultOf(cxt: KdrCxt, identity: AuthIdentityRow): AuthUserRow? =
        pickDefault(identity, registeredUsersOf(cxt, identity))

    /**
     * The identity's registered, enabled users, lowest `userId` first -- the ones the person may act as. From
     * the user cache's `identityId` index when there is one: the cache holds exactly the enabled rows, and this
     * sits on the shell config, fetched on every refresh by every signed-in caller, so it must not be the SQL
     * read `usersOfIdentity` is. SQL only when the cache is absent.
     */
    internal fun registeredUsersOf(cxt: KdrCxt, identity: AuthIdentityRow): List<AuthUserRow> {
        val cache = userCache
        val rows = if (cache != null) {
            cache.checkRefresh(cxt)
            cache.snapshot.allByIndex(AU.identityId, identity.identityId).map { AuthUserRow.extract(it.value, identityOf(cxt)) }
        } else {
            usersOfIdentity(cxt, identity.identityId)
        }
        return rows.filter { it.enabled && it.isRegistered }.sortedBy { it.userId }
    }

    /**
     * The user a person **claims** under the key (identity, [client], [persona], [personaSuffix]) when they prove the
     * address in a way that names no user of theirs -- a Google sign-in with no registered user (issue #749);
     * later, a first login against a client that admits unprovisioned people. An enabled user under the key is
     * registered if it was not; a **disabled** one is re-enabled as an administrator's re-enable leaves it --
     * roles, username, and name kept, activated now -- and registered (the person is behind this, so it is not
     * the unregistered recovery `provisionUser` performs for an administrator); none, and a registered user is
     * created with [roles]. Returns the user.
     */
    fun claimUser(
        cxt: KdrCxt, identity: AuthIdentityRow, client: String, persona: String, personaSuffix: String, roles: List<String>,
    ): AuthUserRow {
        val now = cxt.now()
        val existing = usersOfIdentity(cxt, identity.identityId)
            .firstOrNull { it.client == client && it.persona == persona && it.personaSuffix == personaSuffix && !it.isDeleted }
        if (existing != null) {
            if (!existing.enabled) {
                existing.enabled = true
                existing.activatedAt = now
            }
            if (!existing.isRegistered) existing.registeredAt = now
            updateUser(cxt, existing, isEdit = false) // an activation and a registration, not an edit
            return existing
        }
        val userId = provisionUser(cxt, identity.primaryId, client, roles, createdAt = now, persona = persona, personaSuffix = personaSuffix, registered = true)
        return queryByUserId(cxt, userId)
            ?: throw KdrException("Could not load the just-created user '${identity.primaryId}'.", code = EXC.internalError)
    }

    /**
     * The users the caller may switch to (issue #749): the registered, enabled users of the session's identity,
     * the current one, and the chosen default marked. Empty for a caller no identity backs -- a logged-out
     * caller, a manufactured profile, or a cookie issued before the split.
     */
    fun selfUserChoices(cxt: KdrCxt): List<UserChoice> {
        val profile = cxt.userProfile
        val identityId = profile.identityId ?: return emptyList()
        if (!profile.isLoggedIn) return emptyList()
        val identity = queryIdentityById(cxt, identityId) ?: return emptyList()
        return registeredUsersOf(cxt, identity).map {
            UserChoice(
                it.userId, it.client, it.persona, it.personaSuffix, it.name,
                isCurrent = it.userId == profile.userId, isDefault = it.userId == identity.defaultUserId,
            )
        }
    }

    private fun pickDefault(identity: AuthIdentityRow, users: List<AuthUserRow>): AuthUserRow? {
        if (users.isEmpty()) return null
        // The person's own choices first -- the explicit default, then the one they last acted as -- even when
        // that is a `public` user: they chose it. Only the last resort passes over `public` (issue #752): the
        // earliest user is usually the placeholder a person registered into before anybody placed them, and
        // with a user in a real client that is the one they came for.
        return listOfNotNull(identity.defaultUserId, identity.lastUsedUserId)
            .firstNotNullOfOrNull { wanted -> users.firstOrNull { it.userId == wanted } }
            ?: users.firstOrNull { it.client != CL.public }
            ?: users.first()
    }

    /**
     * Creates the identity at [primaryId] when there is none -- with the address as its contact, and proven
     * when [verifiedAt] says so -- and a user of it in [client] (issue #747): the one provisioning path every
     * flow -- registration, admin create, Google, the fixture -- goes through. [customize] edits the new
     * user's `authUserData` before the insert (a name, the entity flag). Returns the userId.
     *
     * **Provisioning a key that already exists is not a create.** A user with the same (identity, client,
     * persona, personaSuffix) that was deleted *recoverably* (disabled) is **recovered**: the same `userId`, so all
     * of its content comes back, put into the unregistered state -- placeholder username, roles reset to the
     * ones provisioned, activated now -- from which it registers again by the normal mechanism (a code, later
     * an invitation). Its org, name, and entity flag are kept, as the recoverable delete promised; so is the
     * identity's password, which is the person's and not this user's (issue #748). An **enabled** user under
     * the key is refused as a duplicate (the unique index is the backstop behind this check). A permanently
     * deleted one never matches: its tombstone gave up the key. (A person proving an address that names no
     * user of theirs goes through [claimUser] instead, which re-enables rather than recovers.)
     *
     * [registered] says the person is behind this provisioning -- a code proved the address for this user, the
     * test fixture made it, they made it for themself -- so the user is theirs from the start. Absent it, an
     * administrator has provisioned a user for somebody who has yet to claim it.
     *
     * [persona] null takes the persona the [roles] imply (`PERSONA.defaultFor`): personas grant roles by
     * default, and roles provide a default persona, so a user created as an administrator without naming a
     * persona is an `admin`.
     */
    fun provisionUser(
        cxt: KdrCxt,
        primaryId: String,
        client: String,
        roles: List<String>,
        org: String? = null,
        createdAt: Instant? = null,
        persona: String? = null,
        personaSuffix: String = "",
        verifiedAt: Instant? = null,
        /** A chosen username; absent leaves the `@<address>` placeholder for the person to replace. */
        username: String? = null,
        registered: Boolean = false,
        customize: (MutableMap<String, Any?>) -> Unit = {},
    ): Long {
        val persona = persona ?: PERSONA.defaultFor(roles)
        // The one provisioning path, so the one place the key's vocabulary is checked (issue #750): a persona
        // the registry holds, and a personaSuffix within the id rules. Refused as input, whichever surface asked.
        if (PERSONA.def(persona) == null) {
            throw KdrException.mkInput("'$persona' is not a persona; the personas are ${PERSONA.defs.joinToString(", ") { it.name }}.")
        }
        if (!PERSONASUFFIX.isValid(personaSuffix)) {
            throw KdrException.mkInput("'$personaSuffix' is not a valid personaSuffix: up to ${PERSONASUFFIX.maxLength} letters, digits or underscores.")
        }
        val identity = getOrCreateIdentity(cxt, primaryId, verifiedAt)
        val siblings = usersOfIdentity(cxt, identity.identityId)
        // The placeholder username is `@<address>` for an identity's first user, as it always was; `username`
        // is globally unique, so a further user of the same address (issue #747) gets the key appended --
        // `@<address>|<client>|<persona>|<personaSuffix>` -- until the person chooses a real one.
        val placeholder = AuthUserRow.usernameTmpPrefix + if (siblings.isEmpty()) primaryId else "$primaryId|$client|$persona|$personaSuffix"
        siblings.firstOrNull { it.client == client && it.persona == persona && it.personaSuffix == personaSuffix }?.let { existing ->
            if (existing.enabled || existing.isDeleted) {
                // A keyed message with the key's parts as params, and the key as the envelope's logical error
                // code (issue #750): what a surface branches on -- the console offers the personaSuffix box -- so the
                // sentence can be reworded or localized without anything downstream noticing.
                throw KdrException.mkMsg(
                    KdrMsg(AFRAG.auth, AERR.ns, AERR.userKeyTaken),
                    mapOf(
                        AERR.emailParam to primaryId, AERR.clientParam to client, AERR.personaParam to persona,
                        AERR.personaSuffixNoteParam to (if (personaSuffix.isEmpty()) "" else ", personaSuffix '$personaSuffix'"),
                    ),
                ).also { it.extraData[KdrException.errorCodeKey] = AERR.userKeyTaken }
            }
            existing.username = username ?: placeholder
            existing.roles = roles
            if (org != null) existing.org = org
            existing.activatedAt = createdAt
            existing.registeredAt = if (registered) createdAt else null
            customize(existing.authUserData)
            existing.enabled = true
            updateUser(cxt, existing, isEdit = false) // a re-enable, like the admin toggle: not an edit
            return existing.userId
        }
        val data = AuthUserRow.mkInitialUser(identity.identityId, primaryId, client, roles, org, persona, personaSuffix, createdAt, registered).toMutableMap()
        data[AU.username] = username ?: placeholder
        val authUserData: MutableMap<String, Any?> = data[AU.authUserData].toT()
        customize(authUserData)
        return insertUser(cxt, data)
    }

    // --- AuthUsers queries --------------------------------------------------

    private fun authUsersTable(cxt: KdrCxt): KdrTable = cxt.getSchema().tables[UT.authUsers]
        ?: throw KdrException("AuthUsers table is not registered in the schema store.")

    private fun authUserDevicesTable(cxt: KdrCxt): KdrTable = cxt.getSchema().tables[UT.authUserDevices]
        ?: throw KdrException("AuthUserDevices table is not registered in the schema store.")

    /**
     * The user an address logs in as (issue #747): the identity at [primaryId], then its default user
     * ([defaultUserOf]). Null when no identity has the address, or the identity has no user.
     */
    fun queryByPrimaryId(cxt: KdrCxt, primaryId: String): AuthUserRow? =
        queryIdentityByAddress(cxt, primaryId)?.let { defaultUserOf(cxt, it) }

    fun queryByUsername(cxt: KdrCxt, username: String): AuthUserRow? =
        cachedUser(cxt) { it.snapshot.byIndex(AU.username, username) } ?: queryOne(cxt, AU.username, username)

    fun queryByUserId(cxt: KdrCxt, userId: Long): AuthUserRow? =
        cachedUser(cxt) { it.snapshot.get(it.idOf(userId)) } ?: queryOne(cxt, AU.userId, userId)

    /**
     * Serves a single-row lookup from the [userCache] when it holds the row, returning null when it does not
     * -- which every caller above turns into its SQL query, so the cache only ever *saves* a round trip and
     * never changes an answer. A disabled user is the routine miss (the cache holds enabled rows only), as is
     * any lookup made before the cache has loaded.
     *
     * The cache holds the **raw row map**; each hit extracts a fresh [AuthUserRow] from it, exactly as the
     * SQL path does from a queried row. So every caller gets its own mutable row (safe to edit and pass to
     * [updateUser]), the extraction's password scrub applies to what is handed out while the cache keeps
     * full fidelity, and a hit differs from a query by nothing but the round trip. Nested values inside the
     * map (contact lists) remain shared with the cache, so a caller must replace rather than mutate them in
     * place -- which every caller already does.
     */
    private inline fun cachedUser(
        cxt: KdrCxt,
        lookup: (SqlTableCache<Map<String, Any?>>) -> SqlCacheRow<Map<String, Any?>>?,
    ): AuthUserRow? {
        val cache = userCache ?: return null
        cache.checkRefresh(cxt)
        val row = lookup(cache) ?: return null
        return AuthUserRow.extract(row.value, identityOf(cxt))
    }

    /**
     * Resolves a login identifier that is *either* a username *or* a primary contact (email): looks up by
     * username first, then by primaryId. The two spaces are disjoint -- a valid username cannot contain '@'
     * and an email must -- so the fallback is unambiguous. This lets a username-less frontend log a returning
     * user in by email while the backend keeps full username support (issue #70).
     *
     * Like the other [queryByPrimaryId]/[queryByUsername]/[queryByUserId] lookups, this is **identity
     * resolution and is deliberately not client-scoped** (issue #225). It runs before anyone is
     * authenticated -- discovering *which* client the user belongs to is the point of it -- so the acting
     * context is the anonymous one, and filtering by its client would make every user outside that client
     * unable to log in. Scoping belongs on the *administration* reads ([listUsers], [queryAdministrableUser]),
     * where the caller is authenticated and is acting on somebody else's row.
     */
    fun queryByLoginId(cxt: KdrCxt, loginId: String): AuthUserRow? =
        queryByUsername(cxt, loginId) ?: queryByPrimaryId(cxt, loginId)

    /**
     * Loads a user an administrator is allowed to act on: [queryByUserId], but **null when the row falls
     * outside [scope]** (issue #225).
     *
     * Out of scope reads as *absent* rather than *forbidden*, and that is the point: a 403 would confirm that
     * the id belongs to a real user in some other client, which is exactly what a scoped administrator must
     * not be able to probe for. The caller turns null into its own 404.
     */
    fun queryAdministrableUser(cxt: KdrCxt, userId: Long, scope: ReadScope): AuthUserRow? {
        val row = queryByUserId(cxt, userId) ?: return null
        // The one per-row admission predicate, shared with the brute-force search (issue #411), so a by-id
        // read and a listing cannot disagree about what a scope admits.
        return if (scope.admitsUserRow(row.client, row.org, row.userId)) row else null
    }

    /**
     * The users among [userIds] that [scope] admits, keyed by id (issue #562) -- the bulk, scoped counterpart of
     * [queryAdministrableUser], for attaching owners to a listed page rather than resolving one user at a time.
     *
     * Two things the single lookup does not give a caller of many: **scope on every row** (the same
     * `admitsUserRow` predicate, so a listing and a by-id read cannot disagree about what a scope admits -- an
     * out-of-scope id is simply absent from the result), and **one session for the misses**. Ids the cache holds
     * cost no SQL; the rest -- disabled accounts, the routine miss, or every id when the cache is off -- are read
     * in one `withSession` rather than one connection each. Still one statement per missed id: the SQL layer
     * binds no lists, so an `in (...)` waits on that; the ceiling is the page size, which is why this is cheap
     * enough to sit on a listing.
     */
    fun queryUsersByIds(cxt: KdrCxt, userIds: Collection<Long>, scope: ReadScope): Map<Long, AuthUserRow> {
        val found = LinkedHashMap<Long, AuthUserRow>()
        val missed = ArrayList<Long>()
        for (id in userIds.distinct()) {
            val cached = cachedUser(cxt) { it.snapshot.get(it.idOf(id)) }
            if (cached != null) found[id] = cached else missed.add(id)
        }
        if (missed.isNotEmpty()) {
            val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
            val table = authUsersTable(cxt)
            val stmt = SqlTopicUtil.mkNamedTableSelectStmt(sqlCxt, "qAuthUsersBy_${AU.userId}", table, listOf(AU.userId))
            sqlCxt.sqlDb.withSession(cxt) {
                for (id in missed) {
                    sqlCxt.sqlDb.queryOneStatement(cxt, stmt, mapOf(AU.userId to id))?.let { found[id] = AuthUserRow.extract(it, identityOf(cxt)) }
                }
            }
        }
        return found.filterValues { scope.admitsUserRow(it.client, it.org, it.userId) }
    }

    /**
     * Resolves a caller-supplied user reference -- **either a numeric userId or an email** (issue #545) -- to
     * the row, or null when it names no user *or* names one outside [scope]. Two-way by the shape of the value:
     * an all-digit ref is a userId, anything else an email (a primary contact), which is unambiguous because an
     * email always carries an `@`.
     *
     * Scoped by the same per-row predicate as [queryAdministrableUser], and for the same reason: an out-of-scope
     * ref reads as *absent*, so an ordinary caller (whose scope is their own user) can only ever resolve
     * themselves, and no caller can probe whether an id or address belongs to a real user in another client.
     * That is what lets an endpoint accept a `user` param safely -- the confinement is here, not at each call
     * site.
     */
    fun resolveUserRef(cxt: KdrCxt, ref: String, scope: ReadScope): AuthUserRow? {
        // An address is normalized like a login id (issue #743); a numeric id is only trimmed.
        val trimmed = ref.normalizeLoginId()
        if (trimmed.isEmpty()) {
            return null
        }
        val row = trimmed.toLongOrNull()?.let { queryByUserId(cxt, it) } ?: queryByPrimaryId(cxt, trimmed)
        row ?: return null
        return if (scope.admitsUserRow(row.client, row.org, row.userId)) row else null
    }

    /** Selects a single `AuthUsers` row by an indexed [field], or null. Returns the row even if disabled. */
    private fun queryOne(cxt: KdrCxt, field: String, value: Any?): AuthUserRow? {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = authUsersTable(cxt)
        val stmt = SqlTopicUtil.mkNamedTableSelectStmt(sqlCxt, "qAuthUsersBy_$field", table, listOf(field))
        var row: Map<String, Any?>? = null
        sqlCxt.sqlDb.withSession(cxt) {
            row = sqlCxt.sqlDb.queryOneStatement(cxt, stmt, mapOf(field to value))
        }
        return row?.let { AuthUserRow.extract(it, identityOf(cxt)) }
    }

    /**
     * A page of admin user rows plus the total across the whole scope-matched set (issue #499): [rows] is the
     * page (already trimmed to the requested limit), [numAvailable] the count the caller reports so a truncated
     * listing can say how many there are.
     */
    class UserPage(val rows: List<AuthUserRow>, val numAvailable: Int)

    /**
     * Lists `AuthUsers` rows for the admin console, newest first, trimmed to [limit] with the whole-set total in
     * [UserPage.numAvailable] (issue #499). A non-blank [search] is a case-insensitive substring match against
     * `primaryId`, `username`, **or** the account's `name` (a person's full name or a business's).
     *
     * The match is applied in Kotlin after extraction, not in SQL, because `name` lives in `authUserData`
     * -- the same JSON blob as `org`, and unqueryable in SQL for the same reason (see the org note below).
     *
     * **The total costs an extraction only when a post-query filter is active.** `org` and `search` are the two
     * filters applied in Kotlin; when neither narrows -- no search term, and a scope that does not pin an `org`
     * -- the SQL result *is* the scoped set, so the total is its row count and only the page is extracted. With
     * a filter, every matching row must be extracted to count it, which is the price of a true total on a
     * deployment's largest table; the endpoint still maps only the page it keeps.
     *
     * `lower(...) like ?` would not use the plain unique indexes, which is acceptable for an admin-only,
     * human-paced screen; a case-insensitive index is the fix if it ever matters.
     *
     * [scope] is **required, with no default** (issue #225). It defaulted to
     * [ReadScope.unrestricted], which is the fail-open shape this whole seam exists to remove: forgetting the
     * argument returned every row and compiled, ran, and looked correct. A caller that genuinely wants the
     * whole table now has to write [ReadScope.unrestricted] and be seen doing it; everything else should be
     * passing `ReadScopeRules.forCaller`.
     */
    fun listUsers(
        cxt: KdrCxt,
        search: String?,
        limit: Int,
        scope: ReadScope,
    ): UserPage {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = authUsersTable(cxt)
        val term = search?.trim()?.lowercase()?.ifEmpty { null }

        // Every condition goes into the SQL, scope included, so `limit` stays a real cap: filtering after the
        // query would page through rows the caller cannot see in order to fill a page of ones they can. The
        // scope half is composed by SqlScopeUtil rather than spelled out here -- one implementation of
        // scope-to-SQL, so a second scoped query cannot disagree with this one about what a scope means.
        val data = mutableMapOf<String, Any?>()
        val conditions = SqlScopeUtil.scopeConditions(
            scope, table, data,
            // Declared, not defaulted: this table cannot express the organization as a predicate (see the
            // post-filter below), and SqlScopeUtil throws rather than quietly widen the answer.
            filteredAfterQuery = setOf(PF.org),
        )
        // The search term is deliberately *not* an SQL condition: it also matches the account's `name`, which
        // -- like `org` -- lives in `authUserData` and cannot be a predicate. So the whole term is evaluated in
        // Kotlin below, keeping one match rule across all three fields rather than half in SQL.
        val where = if (conditions.isEmpty()) "" else " where " + conditions.joinToString(" and ")

        // Statements are cached by name, so the name carries the query's *shape*: two shapes must not collide,
        // and two callers of the same shape with different values must share rather than cache one each. The
        // term no longer varies the shape (it is applied after the query), so it is out of the name.
        val stmtName = "qAuthUsers${scope.shapeKey}"
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, stmtName, table.columns,
            "select * from t:${UT.authUsers}$where order by c:${AU.userId} desc",
        )
        var rows: List<Map<String, Any?>> = emptyList()
        sqlCxt.sqlDb.withSession(cxt) {
            rows = sqlCxt.sqlDb.queryStatement(cxt, stmt, data)
        }
        // The organization is the one part of the scope that cannot be a predicate: a user's is held in
        // `authUserData`, and querying inside a JSON blob is PostgreSQL-specific and absent from H2. So org (and
        // the name-matching search) are applied here, after extraction.
        //
        // When neither post-query filter narrows -- no search term, and a scope that pins no org (admitsOrg is
        // then always true) -- the SQL rows already *are* the scoped set: the total is their count, and only the
        // page is extracted rather than the whole (possibly enormous) table. With a filter, every matching row
        // must be extracted to count it -- the price of a true total.
        val extracted = rows.asSequence().map { AuthUserRow.extract(it, identityOf(cxt)) }
        if (term == null && scope.org == null) {
            return UserPage(extracted.take(limit).toList(), rows.size)
        }
        val matched = extracted
            .filter { scope.admitsOrg(it.org) }
            .filter { term == null || it.matchesSearch(term) }
            .toList()
        return UserPage(matched.take(limit), matched.size)
    }

    /**
     * The brute-force search/sort over the user cache (issue #411): scans the **active** users the caller may
     * see, applies [criteria]'s filters, sorts, and caps -- reporting the matched total before the cap.
     *
     * Deliberately a memory scan, not an SQL query: it searches and sorts on things that are not database
     * columns (the public name, and -- because the org is not a column -- the whole scope), which is what the
     * user cache makes cheap. The population is `enabledUsers` below, so this covers active users only; the
     * SQL [listUsers] remains the way to reach a disabled or deleted one. [scope] is applied as a per-row
     * predicate (`ReadScope.admitsUserRow`, the same one [queryAdministrableUser] uses), which is the caching
     * skill's "a by-id read may check scope per row" rule -- sound here because a user's scope cannot be an
     * SQL predicate in the first place.
     *
     * [scope] is required and without a default, for the fail-closed reason [listUsers] is: forgetting it must
     * not quietly search every client.
     */
    fun searchUsers(cxt: KdrCxt, criteria: UserSearchCriteria, scope: ReadScope): UserSearchPage {
        val visible = visibleEnabledUsers(cxt, scope).filter { scope.admitsUserRow(it.client, it.org, it.userId) }
        return searchUserRows(visible, criteria)
    }

    /**
     * The active (enabled) users [scope] may see, extracted from the cache when it is present (the cache holds
     * raw maps -- see [AuthUserCache] -- so each is extracted fresh, as `cachedUser` does).
     *
     * When [scope] names a client the rows come from the cache's `client` index rather than the whole table:
     * a client-scoped administrator pays only for their own client, not for every other client's rows they
     * cannot see. That is the caching skill's rule for scoping a listing -- serve it from an index that *is*
     * the scope. An `allClients` administrator (no client in the scope) legitimately searches everyone, so
     * they take the whole live set. The per-row [ReadScope.admitsUserRow] in [searchUsers] still applies the
     * organization narrowing on top, which no index expresses.
     *
     * Falls back to an SQL scan when there is no cache (absent service, or `KDR_TABLE_CACHE_DISABLED`),
     * narrowing to the client in SQL when the scope names one, so the search still answers -- just without the
     * in-memory speed the feature exists for.
     */
    private fun visibleEnabledUsers(cxt: KdrCxt, scope: ReadScope): List<AuthUserRow> {
        // Captured to a local: `scope.client` is a kernel property, so the compiler will not smart-cast it.
        val scopeClient = scope.client
        val cache = userCache
        if (cache != null) {
            cache.checkRefresh(cxt)
            val snapshot = cache.snapshot
            val rows = if (scopeClient != null) snapshot.allByIndex(PF.client, scopeClient) else snapshot.byId.values
            return rows.map { AuthUserRow.extract(it.value, identityOf(cxt)) }
        }
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = authUsersTable(cxt)
        val data = mutableMapOf<String, Any?>(PF.enabled to true)
        var where = "where c:${PF.enabled} = :${PF.enabled}"
        if (scopeClient != null) {
            where += " and c:${PF.client} = :${PF.client}"
            data[PF.client] = scopeClient
        }
        // The name carries the query's shape: with or without the client predicate, so the two do not collide.
        val stmtName = "qAuthUsersEnabled" + if (scopeClient != null) "ByClient" else ""
        val stmt = SqlStmtUtil.prepareSql(sqlCxt, stmtName, table.columns, "select * from t:${UT.authUsers} $where")
        var rows: List<Map<String, Any?>> = emptyList()
        sqlCxt.sqlDb.withSession(cxt) {
            rows = sqlCxt.sqlDb.queryStatement(cxt, stmt, data)
        }
        return rows.map { AuthUserRow.extract(it, identityOf(cxt)) }
    }

    /** Inserts a new `AuthUsers` row (protocol columns stamped), returning the generated `userId`. */
    fun insertUser(cxt: KdrCxt, data: Map<String, Any?>): Long {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = authUsersTable(cxt)
        val stmt = SqlTopicUtil.mkTableInsertStmt(sqlCxt, table)
        val row = data.toMutableMap()
        SqlTopicUtil.prepForStdExecute(cxt, table, row) // stamps enabled=true, client, audit columns
        val counter = LongArray(1)
        sqlCxt.sqlDb.withSession(cxt) {
            sqlCxt.sqlDb.executeStatementGetCounterBack(cxt, stmt, row, counter)
        }
        return counter[0]
    }

    /**
     * Writes [row] back to its `AuthUsers` record (by `userId`), re-stamping protocol columns.
     *
     * The update is **version-guarded**: it matches the `updatedAt` the row was read with, so a row that has
     * been changed since -- by another request, another node, or an admin -- refuses with a conflict rather
     * than being silently overwritten whole. This write replaces the entire row, which is what makes the
     * guard necessary: an unguarded whole-row write built from a stale read reverts every intervening change
     * (a role grant, a disable) without any error, and reading through the cache stretches how stale the base
     * row can be. On a refusal the cache is marked so the very next read is fresh; the caller re-reads and
     * retries, now working from the row that actually exists.
     */
    fun updateUser(cxt: KdrCxt, row: AuthUserRow, isEdit: Boolean = true) {
        // `lastEditedAt` moves on an ordinary write and is opted *out* of, not into (issue #462). The failure
        // modes are not symmetric: a new edit path that forgot to opt in would silently stop tracking, which
        // nothing would ever show, while a non-edit write that forgets to opt out moves a timestamp it should
        // not -- rarer, and visible. Two callers opt out today: the login stamp and re-enabling an account.
        if (isEdit) {
            row.lastEditedAt = cxt.now()
        }
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = authUsersTable(cxt)
        val data = row.toMap().toMutableMap()
        // The version this row was read at, captured before prepForStdExecute stamps the new one.
        val priorUpdatedAt = data[PF.updatedAt].toOptInstant()
        SqlTopicUtil.prepForStdExecute(cxt, table, data)
        // prepForStdExecute stamps `enabled = true` unconditionally -- deliberate for a "create" that revives a
        // disabled row (issue #48), but wrong for an update, where it would make disabling a user impossible:
        // the write would silently succeed, leaving the row enabled. The caller's intent wins here.
        data[PF.enabled] = row.enabled
        var count = 1
        if (priorUpdatedAt == null) {
            // No version to guard on -- a row never read from the database. Not a path any current caller
            // takes (updates start from a query), but refusing it outright would turn a programming slip into
            // a data-shaped mystery; the plain update keeps the old semantics for it.
            val stmt = SqlTopicUtil.mkTableUpdateStmt(sqlCxt, table)
            sqlCxt.sqlDb.withSession(cxt) { sqlCxt.sqlDb.executeStatement(cxt, stmt, data) }
        } else {
            data[priorUpdatedAtParam] = priorUpdatedAt
            val stmt = mkGuardedUserUpdateStmt(sqlCxt, table)
            sqlCxt.sqlDb.withSession(cxt) { count = sqlCxt.sqlDb.executeStatement(cxt, stmt, data) }
        }
        if (count == 0) {
            // The guarded write matched nothing: the row moved under us. Mark the cache so the caller's
            // re-read is fresh rather than the same stale row that produced this conflict.
            SqlTableCacheService.get(cxt).noteTableChanged(cxt, UT.authUsers)
            throw KdrException(
                "User ${row.userId} was modified concurrently; re-read the user and retry the change.",
                code = EXC.conflict,
            )
        }
        // Success: advance the version the row object carries, so a flow that updates the same row twice
        // (login does: the update, then completeLogin's auto-admin sync) guards its second write against the
        // row it just wrote rather than the original read. The typed `updatedAt` is advanced alongside the raw
        // map, or a handler that returns `row.toAdminInfo()` after the write would report the pre-write time
        // (the search surfaces updatedAt -- issue #411).
        val newUpdatedAt = data[PF.updatedAt]
        row.data = row.data.toMutableMap().also { it[PF.updatedAt] = newUpdatedAt }
        row.updatedAt = newUpdatedAt.toOptInstant()
    }

    /**
     * Deletes a user, in the two senses of the word (issue #396).
     *
     *  - **Recoverable** ([permanent] false): the account is merely disabled. A disabled row cannot log in and
     *    its live roles are empty (`AuthUserUtil.refreshActingRoles`), but every identifier and contact is kept,
     *    so re-enabling it restores the user intact. This is what the `setEnabled(false)` toggle already does;
     *    delete is the same operation under a name that says what it is for.
     *  - **Permanent** ([permanent] true): the account is disabled **and de-identified** -- its username
     *    obfuscated and its key given up ([AuthUserRow.deletedTombstone]) -- and, when it was its identity's
     *    last live user, the identity is **retired** with it ([AuthIdentityRow.retire]): the address obfuscated
     *    and freed for re-registration, the password and contacts cleared, the external logins and remembered
     *    devices purged. The row survives only as a `deleted-<userId>` tombstone.
     *
     * Returns the resulting row, so a caller reports the outcome (the obfuscated identifiers included) without
     * a re-read. The de-identifying write goes through [updateUser], so it keeps the optimistic-concurrency
     * guard and announces itself to the user cache; the identity's retirement and the auxiliary-table purges
     * run only after it succeeds, so a version conflict leaves nothing half-removed.
     */
    fun deleteUser(cxt: KdrCxt, row: AuthUserRow, permanent: Boolean): AuthUserRow {
        val result = if (permanent) {
            AuthUserRow.deletedTombstone(row, cxt.instanceNow(), cxt.userProfile.userId)
        } else {
            row.also { it.enabled = false }
        }
        updateUser(cxt, result)
        if (permanent) {
            // The address, credentials, external logins and devices are the identity's (issues #747, #748). They
            // are retired -- freeing the address for re-registration, as a permanent delete always has -- only
            // when this was the identity's last live user (a recoverably deleted sibling counts as live: it can
            // be re-enabled): a person's other users elsewhere are not this administrator's to erase.
            queryIdentityById(cxt, row.identityId)?.let { identity ->
                val others = usersOfIdentity(cxt, identity.identityId).filter { it.userId != row.userId && !it.isDeleted }
                if (others.isEmpty()) {
                    identity.retire(row.userId)
                    updateIdentity(cxt, identity)
                    deleteRowsForIdentity(cxt, UT.linkedUsers, identity.identityId)
                    deleteRowsForIdentity(cxt, UT.authUserDevices, identity.identityId)
                }
            }
        }
        return result
    }

    /** Hard-deletes every row of [tableName] belonging to [identityId] -- the auxiliary tables a retired
     *  identity purges. A real delete, not a soft one: these rows are login paths and device memory, not
     *  the audit record, which is the surviving (disabled, obfuscated) `AuthUsers` tombstone. */
    private fun deleteRowsForIdentity(cxt: KdrCxt, tableName: String, identityId: String) {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = cxt.getSchema().tables[tableName]
            ?: throw KdrException("$tableName table is not registered in the schema store.")
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "dPurge$tableName", table.columns,
            "delete from t:$tableName where c:${AI.identityId} = :${AI.identityId}",
        )
        sqlCxt.sqlDb.withSession(cxt) { sqlCxt.sqlDb.executeStatement(cxt, stmt, mapOf(AI.identityId to identityId)) }
    }

    /**
     * The update-by-userId statement with the optimistic-concurrency condition added: `... and updatedAt =
     * :priorUpdatedAt`. The bind parameter needs its own column definition (cloned from `updatedAt`, so it
     * binds as a date) because the standard update already binds `updatedAt` to the *new* value in its SET
     * clause -- one name cannot carry both.
     */
    private fun mkGuardedUserUpdateStmt(sqlCxt: SqlCxt, table: KdrTable): SqlStatement {
        val setColumns = table.columns.filter { col ->
            col.name != PF.touchedAt && col.name != PF.createdAt && col.name != PF.createdBy && !col.autoIncrement
        }
        val query = SqlStmtUtil.mkUpdateQuery(table.tableName, setColumns, table.primaryKey) +
            " AND c:${PF.updatedAt} = :$priorUpdatedAtParam"
        val updatedAtCol = table.columnsByName.getValue(PF.updatedAt)
        val priorCol = KdrColumn(
            priorUpdatedAtParam, updatedAtCol.schema, updatedAtCol.storeType, updatedAtCol.isList,
            required = false, autoIncrement = false,
        )
        return SqlStmtUtil.prepareSql(sqlCxt, "uAuthUsersGuarded", table.columns + priorCol, query)
    }

    // --- LinkedUsers: external identities -----------------------------------

    private fun linkedUsersTable(cxt: KdrCxt): KdrTable = cxt.getSchema().tables[UT.linkedUsers]
        ?: throw KdrException("LinkedUsers table is not registered in the schema store.")

    /**
     * The local identity an external one signs in as, or null when it has never been linked. Keyed by the
     * source's own id ([LU.linkId]) rather than by email, so a provider changing or reassigning the email on
     * an account can never re-point an existing link. Which user the person then acts as is [defaultUserOf]'s
     * answer, as for any other login (issue #748).
     */
    fun queryLinkedIdentity(cxt: KdrCxt, linkSource: String, linkId: String): AuthIdentityRow? {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = linkedUsersTable(cxt)
        val stmt = SqlTopicUtil.mkTableSelectStmt(sqlCxt, table)
        var row: Map<String, Any?>? = null
        sqlCxt.sqlDb.withSession(cxt) {
            row = sqlCxt.sqlDb.queryOneStatement(cxt, stmt, mapOf(LU.linkSource to linkSource, LU.linkId to linkId))
        }
        val identityId = row?.get(AI.identityId).toOptStr() ?: return null
        return queryIdentityById(cxt, identityId)
    }

    /**
     * Links an external identity to the local [identityId]. [linkData] holds the claims the source supplied
     * at link time (its email, display name) -- captured for support and for showing the person what is
     * linked, never read back as an authority on identity.
     */
    fun insertLinkedIdentity(
        cxt: KdrCxt, linkSource: String, linkId: String, identityId: String, linkData: Map<String, Any?>,
    ) {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = linkedUsersTable(cxt)
        val stmt = SqlTopicUtil.mkTableInsertStmt(sqlCxt, table)
        val row = mutableMapOf<String, Any?>(
            LU.linkSource to linkSource,
            LU.linkId to linkId,
            LU.linkData to linkData,
            AI.identityId to identityId,
        )
        SqlTopicUtil.prepForStdExecute(cxt, table, row)
        sqlCxt.sqlDb.withSession(cxt) {
            sqlCxt.sqlDb.executeStatement(cxt, stmt, row)
        }
    }

    // --- AuthUserDevices: familiar-device trust -----------------------------

    /**
     * Records the device an identity logged in from, and -- when [markTrusted] -- marks it *familiar*
     * (verified) with a fresh [AUTHC.deviceTrustMillis] expiration. Only a verification-code login sets
     * [markTrusted] (see KdrRequest.trustDevice); a password login records presence but never grants trust.
     * Upserts the row keyed by ([identityId], [deviceGuid]) -- the person's, whichever of their users they
     * logged in as (issue #748): an existing untrusted row is left untouched when there is no trust to grant.
     * The multi-IP/user-agent merge is still deferred; deviceData holds the latest only.
     */
    fun recordDevice(
        cxt: KdrCxt, identityId: String, deviceGuid: String, ipAddress: String?, userAgent: String?, markTrusted: Boolean,
    ) {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = authUserDevicesTable(cxt)
        val key = mapOf(AI.identityId to identityId, AUD.deviceGuid to deviceGuid)
        sqlCxt.sqlDb.withSession(cxt) {
            val existing = sqlCxt.sqlDb.queryOneStatement(cxt, SqlTopicUtil.mkTableSelectStmt(sqlCxt, table), key)
            if (existing != null && !markTrusted) return@withSession // presence already recorded; nothing to add
            val expiration = if (markTrusted) {
                Instant.fromEpochMilliseconds(cxt.now().toEpochMilliseconds() + AUTHC.deviceTrustMillis)
            } else {
                null
            }
            val row = mutableMapOf(
                AI.identityId to identityId,
                AUD.deviceGuid to deviceGuid,
                AUD.deviceData to mapOf("ipAddress" to ipAddress, "userAgent" to userAgent),
                AUD.deviceVerified to markTrusted,
                AUD.verifyExpiration to expiration,
            )
            SqlTopicUtil.prepForStdExecute(cxt, table, row)
            val stmt = if (existing != null) SqlTopicUtil.mkTableUpdateStmt(sqlCxt, table)
            else SqlTopicUtil.mkTableInsertStmt(sqlCxt, table)
            sqlCxt.sqlDb.executeStatement(cxt, stmt, row)
        }
    }

    /**
     * Whether [deviceGuid] is a *familiar* device for [identityId]: a recorded row that is verified and whose
     * trust has not expired. This is the hard precondition for password login (issue #69) -- an unfamiliar
     * device cannot use a password at all and must fall back to a verification code.
     */
    fun isDeviceTrusted(cxt: KdrCxt, identityId: String, deviceGuid: String): Boolean {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = authUserDevicesTable(cxt)
        val stmt = SqlTopicUtil.mkTableSelectStmt(sqlCxt, table)
        var row: Map<String, Any?>? = null
        sqlCxt.sqlDb.withSession(cxt) {
            row = sqlCxt.sqlDb.queryOneStatement(cxt, stmt, mapOf(AI.identityId to identityId, AUD.deviceGuid to deviceGuid))
        }
        val r = row ?: return false
        if (r[AUD.deviceVerified] != true) return false
        val expiration = r[AUD.verifyExpiration].toOptInstant() ?: return false
        return cxt.now() <= expiration
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "UserService"

        /** Bind-parameter name for the version guard in [updateUser]'s WHERE clause (name matches value). */
        const val priorUpdatedAtParam = "priorUpdatedAt"

        fun get(cxt: KdrCxt): UserService = cxt.instanceConfig.get(serviceName) as? UserService
            ?: throw KdrException("The $serviceName is not available on this node.")

        /** The service, or null on a node that carries no account machinery -- an edge (see `CommonComponent`). */
        fun getOrNull(cxt: KdrCxt): UserService? = cxt.instanceConfig.get(serviceName) as? UserService
    }
}
