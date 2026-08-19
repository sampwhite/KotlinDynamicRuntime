package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
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
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLong
import kotlin.time.Instant

/**
 * The user/auth service (issue #67): owns the [AuthFormHandler] and the SQL access to the `AuthUsers` and
 * `AuthUserDevices` tables. Ported from dn's `UserService` + `AuthQueryHolder`, using kd2's topic/table SQL
 * layer. Registered by the `common` component; found via [get].
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

    override fun checkInit(cxt: KdrCxt) {
        if (::authFormHandler.isInitialized) return
        val node = NodeService.get(cxt)
        val mail = MailService.get(cxt)
        // Null when the deployment configured no Google client id, which is what disables Google sign-in.
        val googleVerifier = GoogleAuthConfig.mkVerifier(cxt.instanceConfig)
        authFormHandler = AuthFormHandler(this, node, mail, googleVerifier)
        // Registered during this pass so the table-cache service's own checkReady -- which runs after every
        // service's checkInit -- finds it and performs the initial load at startup rather than in a request.
        userCache = AuthUserCache.register(cxt)
    }

    // --- AuthUsers queries --------------------------------------------------

    private fun authUsersTable(cxt: KdrCxt): KdrTable = cxt.getSchema().tables[UT.authUsers]
        ?: throw KdrException("AuthUsers table is not registered in the schema store.")

    private fun authUserDevicesTable(cxt: KdrCxt): KdrTable = cxt.getSchema().tables[UT.authUserDevices]
        ?: throw KdrException("AuthUserDevices table is not registered in the schema store.")

    fun queryByPrimaryId(cxt: KdrCxt, primaryId: String): AuthUserRow? =
        cachedUser(cxt) { it.snapshot.byIndex(AU.primaryId, primaryId) } ?: queryOne(cxt, AU.primaryId, primaryId)

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
        return AuthUserRow.extract(row.value)
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
        if (scope.client != null && row.client != scope.client) return null
        if (!scope.admitsOrg(row.org)) return null
        if (scope.userId != null && row.userId != scope.userId) return null
        return row
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
        return row?.let { AuthUserRow.extract(it) }
    }

    /**
     * Lists `AuthUsers` rows for the admin console, newest first, capped at [limit]. A non-blank [search] is a
     * case-insensitive substring match against `primaryId`, `username`, **or** the account's `name` (a
     * person's full name or a business's).
     *
     * The match is applied in Kotlin after extraction, not in SQL, because `name` lives in `authUserData`
     * -- the same JSON blob as `org`, and unqueryable in SQL for the same reason (see the org note below). The
     * query carries no SQL `LIMIT` anyway; the cap is `.take(limit)` here, and the newest-first default view
     * already selects every scoped row, so evaluating the term in Kotlin only lifts a typed search to that same
     * baseline rather than introducing a new full scan.
     *
     * The filter is applied in SQL (not by loading the table and filtering in Kotlin) so the cap is a real one:
     * a deployment's user table is the one table guaranteed to outgrow any page size. `lower(...) like ?` will
     * not use the plain unique indexes, which is acceptable for an admin-only, human-paced screen; a
     * case-insensitive index is the fix if it ever matters.
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
    ): List<AuthUserRow> {
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
        // `authUserData`, and querying inside a JSON blob is PostgreSQL-specific and absent from H2. So it is
        // applied here, after extraction -- which is also why `limit` is taken *after* the filter rather than
        // in SQL: capping first would return short pages made of rows the caller may not see. The cap
        // therefore costs more rows read than returned under an org scope; acceptable on a human-paced admin
        // screen, and the reason content gets a real column instead.
        return rows.asSequence()
            .map { AuthUserRow.extract(it) }
            .filter { scope.admitsOrg(it.org) }
            .filter { term == null || it.matchesSearch(term) }
            .take(limit)
            .toList()
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
    fun updateUser(cxt: KdrCxt, row: AuthUserRow) {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = authUsersTable(cxt)
        val data = row.toMap().toMutableMap()
        // The version this row was read at, captured before prepForStdExecute stamps the new one.
        val priorUpdatedAt = data[PF.updatedAt].toOptInstant()
        SqlTopicUtil.prepForStdExecute(cxt, table, data)
        // prepForStdExecute stamps `enabled = true` unconditionally -- deliberate for a "create" that revives a
        // disabled row (issue #48), but wrong for an update, where it would make disabling a user impossible:
        // the write would silently succeed and the row stay enabled. The caller's intent wins here.
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
        // row it just wrote rather than the original read.
        row.data = row.data.toMutableMap().also { it[PF.updatedAt] = data[PF.updatedAt] }
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
     * The user an external identity signs in as, or null when that identity has never been linked. Keyed by
     * the source's own id ([LU.linkId]) rather than by email, so a provider changing or reassigning the email
     * on an account can never re-point an existing link.
     */
    fun queryLinkedUser(cxt: KdrCxt, linkSource: String, linkId: String): AuthUserRow? {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = linkedUsersTable(cxt)
        val stmt = SqlTopicUtil.mkTableSelectStmt(sqlCxt, table)
        var row: Map<String, Any?>? = null
        sqlCxt.sqlDb.withSession(cxt) {
            row = sqlCxt.sqlDb.queryOneStatement(cxt, stmt, mapOf(LU.linkSource to linkSource, LU.linkId to linkId))
        }
        val userId = row?.get(AU.userId).toOptLong() ?: return null
        return queryByUserId(cxt, userId)
    }

    /**
     * Links an external identity to [userId]. [linkData] holds the claims the source supplied at link time
     * (its email, display name) -- captured for support and for showing the user what is linked, never read
     * back as an authority on identity.
     */
    fun insertLinkedUser(
        cxt: KdrCxt, linkSource: String, linkId: String, userId: Long, linkData: Map<String, Any?>,
    ) {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = linkedUsersTable(cxt)
        val stmt = SqlTopicUtil.mkTableInsertStmt(sqlCxt, table)
        val row = mutableMapOf<String, Any?>(
            LU.linkSource to linkSource,
            LU.linkId to linkId,
            LU.linkData to linkData,
            AU.userId to userId,
        )
        SqlTopicUtil.prepForStdExecute(cxt, table, row)
        sqlCxt.sqlDb.withSession(cxt) {
            sqlCxt.sqlDb.executeStatement(cxt, stmt, row)
        }
    }

    // --- AuthUserDevices: familiar-device trust -----------------------------

    /**
     * Records the device a user logged in from, and -- when [markTrusted] -- marks it *familiar* (verified)
     * with a fresh [AUTHC.deviceTrustMillis] expiration. Only a verification-code login sets [markTrusted]
     * (see KdrRequest.trustDevice); a password login records presence but never grants trust. Upserts the
     * row keyed by ([userId], [deviceGuid]): an existing untrusted row is left untouched when there is no
     * trust to grant. The multi-IP/user-agent merge is still deferred; deviceData holds the latest only.
     */
    fun recordDevice(
        cxt: KdrCxt, userId: Long, deviceGuid: String, ipAddress: String?, userAgent: String?, markTrusted: Boolean,
    ) {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = authUserDevicesTable(cxt)
        val key = mapOf(AU.userId to userId, AUD.deviceGuid to deviceGuid)
        sqlCxt.sqlDb.withSession(cxt) {
            val existing = sqlCxt.sqlDb.queryOneStatement(cxt, SqlTopicUtil.mkTableSelectStmt(sqlCxt, table), key)
            if (existing != null && !markTrusted) return@withSession // presence already recorded; nothing to add
            val expiration = if (markTrusted) {
                Instant.fromEpochMilliseconds(cxt.now().toEpochMilliseconds() + AUTHC.deviceTrustMillis)
            } else {
                null
            }
            val row = mutableMapOf(
                AU.userId to userId,
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
     * Whether [deviceGuid] is a *familiar* device for [userId]: a recorded row that is verified and whose
     * trust has not expired. This is the hard precondition for password login (issue #69) -- an unfamiliar
     * device cannot use a password at all and must fall back to a verification code.
     */
    fun isDeviceTrusted(cxt: KdrCxt, userId: Long, deviceGuid: String): Boolean {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = authUserDevicesTable(cxt)
        val stmt = SqlTopicUtil.mkTableSelectStmt(sqlCxt, table)
        var row: Map<String, Any?>? = null
        sqlCxt.sqlDb.withSession(cxt) {
            row = sqlCxt.sqlDb.queryOneStatement(cxt, stmt, mapOf(AU.userId to userId, AUD.deviceGuid to deviceGuid))
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
    }
}
