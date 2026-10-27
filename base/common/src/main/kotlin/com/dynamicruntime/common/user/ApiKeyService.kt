package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.isSandboxClient
import com.dynamicruntime.common.sql.KdrColumn
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlScopeUtil
import com.dynamicruntime.common.sql.SqlStatement
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.sql.cache.SqlCacheParams
import com.dynamicruntime.common.sql.cache.SqlTableCache
import com.dynamicruntime.common.sql.cache.SqlTableCacheService
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.RandomUtil
import com.dynamicruntime.common.util.base64Encode
import com.dynamicruntime.common.util.constantTimeEquals
import com.dynamicruntime.common.util.sha256Hex
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import java.util.concurrent.ConcurrentHashMap

/**
 * Which API key a request authenticated with (issue #1402), on [KdrCxt.apiKey], and when its use was last recorded. A
 * keyed request sets no cookie, and what it may not reach is [refusal]'s to say.
 */
class ApiKeyAccess(
    val keyId: String,
    val access: String,
    val lastUsedAt: kotlin.time.Instant? = null,
    /** When the key expires, which no token exchanged for it may outlast (issue #1403). */
    val expiresAt: kotlin.time.Instant? = null,
    /** Whether the request sent an access token exchanged for the key (issue #1403), rather than the key itself. */
    val viaToken: Boolean = false,
) {
    val isReadOnly: Boolean get() = access == AKEY.read

    /**
     * Why this key may not make a [method] request of an endpoint that is [sessionOnly] -- or, for a request no endpoint
     * answers (a content page), of [path] -- or null when it may. A [read][AKEY.read] key makes GET requests and nothing
     * else; no key reaches a [session-only][com.dynamicruntime.common.endpoint.KdrEndpoint.sessionOnly] endpoint. The
     * one statement of it: the dispatcher refuses with it, and the endpoint catalog leaves out what it refuses, so a
     * keyed caller is never shown an endpoint that would then refuse them.
     *
     * The token exchange ([ATOK.token], issue #1403) is the one exception, and in both directions: a read-only key may
     * post to it, since asking for a token changes nothing, and a token may not, since it is the key that is exchanged
     * -- one token buying the next would make a token live forever.
     */
    fun refusal(method: String, sessionOnly: Boolean, path: String): String? = when {
        path == ATOK.token -> if (viaToken) "An access token is not exchanged for another; send the API key itself." else null
        isReadOnly && method != HttpMethod.GET.name -> "This API key is read-only: it may only make GET requests."
        sessionOnly -> "'$path' is not open to API keys; it needs a signed-in session."
        else -> null
    }
}

/** A key just made: its info and the whole key, `kdr_<keyId>_<secret>`, which is never seen again. */
class ApiKeyCreated(val info: ApiKeyInfo, val secret: String)

/** One key on an administrator's listing, with the user it acts as. */
class ListedApiKey(val row: Map<String, Any?>, val owner: AuthUserRow)

/**
 * API keys (issue #1402): made for a user by the user or by an administrator, sent by a program as
 * `Authorization: Bearer kdr_<keyId>_<secret>`, and honored as that user -- never above them -- until the key expires
 * or is revoked.
 *
 * **Only a hash of the secret is kept** ([APK.secretHash], SHA-256: the secret is 256 random bits, so a slow hash, which
 * is what a password a person chose needs, buys nothing). The key's public [AKEY.keyId] finds the row, and the hashes
 * are compared in constant time.
 *
 * Every keyed request looks its key up, so the table is held in memory (`kdr-table-cache`): it is small -- at most
 * [AKEY.maxLiveKeys] live keys a user -- and its rows are read one by one, by id. A revoked key is disabled, which makes
 * it a tombstone in the cache and a disabled row to the SQL fallback, so a revocation bites on every node within the
 * cache's state-row throttle.
 */
class ApiKeyService : ServiceInitializer {
    override val serviceName: String = ApiKeyService.serviceName

    /** The in-memory `AuthApiKeys` cache, or null without the cache service: every lookup then reads SQL. */
    var keyCache: SqlTableCache<Map<String, Any?>>? = null

    @Volatile
    private var initialized = false

    /** Failed keys per source address, so a run of wrong keys is slowed rather than answered at full speed. */
    private val failures = AuthRateLimiter()

    /** When this node last stamped each key's [AKEY.lastUsedAt], so a busy key writes about once an hour. */
    private val lastUseWritten = ConcurrentHashMap<String, Long>()

    override fun checkInit(cxt: KdrCxt) {
        if (initialized) return
        initialized = true
        // Registered in this pass, so the cache service's own checkReady loads it at startup rather than in a request.
        keyCache = SqlTableCacheService.registerCache(
            cxt, SqlCacheParams(topic = authTopic, tableName = UT.authApiKeys, extract = { _, data -> data }, indexes = emptyList()),
        )
    }

    fun table(cxt: KdrCxt): KdrTable = cxt.getGlobalSchema().tables[UT.authApiKeys]
        ?: throw KdrException("The ${UT.authApiKeys} table is not registered in the schema store.")

    // --- reading -------------------------------------------------------------------------------------------------

    /**
     * The row of the key [keyId], enabled or not, or null when there is none. The cache holds enabled keys only, so a
     * revoked key -- and an id nobody issued -- is the routine miss, read from SQL.
     *
     * [forWrite] reads SQL alone: a cached row's [AKEY.lastUsedAt] may be behind, since the last-use stamp leaves
     * `updatedAt` as it is and so never reloads the cache, and a change written back from it would erase the stamp.
     */
    fun queryKey(cxt: KdrCxt, keyId: String, forWrite: Boolean = false, onMiss: () -> Unit = {}): Map<String, Any?>? {
        if (!forWrite) keyCache?.let { cache ->
            cache.checkRefresh(cxt)
            cache.snapshot.get(cache.idOf(keyId))?.let { return it.value }
            onMiss()
            cache.guardFallback(cxt)
        }
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val stmt = SqlTopicUtil.mkTableSelectStmt(sqlCxt, table(cxt))
        var row: Map<String, Any?>? = null
        sqlCxt.sqlDb.withSession(cxt) { row = sqlCxt.sqlDb.queryOneStatement(cxt, stmt, mapOf(AKEY.keyId to keyId)) }
        return row
    }

    /** Every key of the user [userId] in [client], revoked and expired ones included, newest first. */
    fun keysOf(cxt: KdrCxt, client: String, userId: Long): List<Map<String, Any?>> {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qAuthApiKeysOfUser", table(cxt).columns,
            "select * from t:${UT.authApiKeys} where c:${PF.client} = :${PF.client} and c:${PF.userId} = :${PF.userId} " +
                "order by c:${PF.createdAt} desc, c:${AKEY.keyId} desc",
        )
        var rows: List<Map<String, Any?>> = emptyList()
        sqlCxt.sqlDb.withSession(cxt) {
            rows = sqlCxt.sqlDb.queryStatement(cxt, stmt, mapOf(PF.client to client, PF.userId to userId))
        }
        return rows
    }

    /**
     * The keys [scope] admits that [keep] keeps, each with its owner, soonest to expire first -- an administrator's
     * listing, cut to [limit] -- and how many there are in all. [userId] narrows it to one user's.
     *
     * The table has no organization: a key belongs to its owner, and the owner's organization lives with the user. So
     * the scope's organization is applied to each owner after the query ([SqlScopeUtil]'s `filteredAfterQuery`, as
     * `UserService.listUsers` does), by the same per-row predicate a by-id read of a user asks
     * ([ReadScope.admitsUserRow]). An owner the scope does not admit, or one that is gone, leaves its keys unlisted.
     * Only then must every key's owner be read; a scope that names no organization reads the owners of the page alone.
     */
    fun listScoped(
        cxt: KdrCxt, scope: ReadScope, userId: Long?, limit: Int, keep: (Map<String, Any?>) -> Boolean = { true },
    ): Pair<List<ListedApiKey>, Int> {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val (stmt, data) = listStatement(cxt, scope, userId)
        var rows: List<Map<String, Any?>> = emptyList()
        sqlCxt.sqlDb.withSession(cxt) { rows = sqlCxt.sqlDb.queryStatement(cxt, stmt, data) }
        val kept = rows.filter(keep)
        fun withOwners(of: List<Map<String, Any?>>): List<ListedApiKey> {
            val owners = UserService.get(cxt).queryUsersByIds(cxt, of.mapNotNull { it[PF.userId].toOptLong() }, scope)
            return of.mapNotNull { row -> owners[row[PF.userId].toOptLong()]?.let { ListedApiKey(row, it) } }
        }
        if (scope.org == null) return withOwners(kept.take(limit)) to kept.size
        val admitted = withOwners(kept)
        return admitted.take(limit) to admitted.size
    }

    /** [listScoped]'s query for [scope] and [userId], with the values it binds. */
    fun listStatement(cxt: KdrCxt, scope: ReadScope, userId: Long?): Pair<SqlStatement, Map<String, Any?>> {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = table(cxt)
        val data = mutableMapOf<String, Any?>()
        val conditions = SqlScopeUtil.scopeConditions(scope, table, data, filteredAfterQuery = setOf(PF.org)).toMutableList()
        if (userId != null) {
            // Its own parameter: the scope may already bind `userId` (a caller confined to their own rows).
            conditions.add("c:${PF.userId} = :$listUserParam")
            data[listUserParam] = userId
        }
        val where = if (conditions.isEmpty()) "" else " where " + conditions.joinToString(" and ")
        // The parameter needs its own column definition (cloned from `userId`, so it binds as a number): a name no
        // column has binds as a string, which H2 compares with a number and Postgres refuses.
        val userIdCol = table.columnsByName.getValue(PF.userId)
        val listUserCol = KdrColumn(
            listUserParam, userIdCol.schema, userIdCol.storeType, userIdCol.isList, required = false, autoIncrement = false,
        )
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qAuthApiKeys${scope.shapeKey}${if (userId != null) "U" else ""}", table.columns + listUserCol,
            "select * from t:${UT.authApiKeys}$where order by c:${AKEY.expiresAt} asc, c:${AKEY.keyId} asc",
        )
        return stmt to data
    }

    /** [row] as it is listed; [owner] adds the owner's name and address, and [creatorName] who made it. */
    fun infoOf(row: Map<String, Any?>, owner: AuthUserRow? = null, creatorName: String? = null): ApiKeyInfo = ApiKeyInfo(
        keyId = row[AKEY.keyId].toOptStr().orEmpty(),
        name = row[AKEY.name].toOptStr().orEmpty(),
        access = row[AKEY.access].toOptStr() ?: AKEY.read,
        userId = row[PF.userId].toOptLong() ?: 0L,
        client = row[PF.client].toOptStr().orEmpty(),
        createdAt = row[PF.createdAt].toOptInstant(),
        createdBy = row[PF.createdBy].toOptLong(),
        createdByName = creatorName,
        expiresAt = row[AKEY.expiresAt].toOptInstant() ?: throw KdrException("API key '${row[AKEY.keyId]}' has no expiry."),
        lastUsedAt = row[AKEY.lastUsedAt].toOptInstant(),
        revokedAt = row[AKEY.revokedAt].toOptInstant(),
        revokedBy = row[AKEY.revokedBy].toOptLong(),
        ownerName = owner?.name,
        ownerAddress = owner?.primaryId,
    )

    /** How many of [rows] still let anyone in at [cxt]'s now: neither revoked nor expired. */
    fun liveCount(cxt: KdrCxt, rows: List<Map<String, Any?>>): Int {
        val now = cxt.now()
        return rows.count { infoOf(it).isUsable(now) }
    }

    // --- writing -------------------------------------------------------------------------------------------------

    /**
     * Makes a key for [owner], named [name], with [access], lasting [days] (the deployment's [ApiKeyLifetime.days] when
     * null). [owner] is the user the key acts as; whoever is signed in -- the owner, or an administrator for them -- is
     * recorded as its maker (`createdBy`). Answers the key's info and the whole key, the one time it exists outside the
     * request that made it.
     */
    fun create(cxt: KdrCxt, owner: AuthUserRow, name: String?, access: String?, days: Int?): ApiKeyCreated {
        val keyName = name?.trim().orEmpty()
        if (keyName.isEmpty()) throw KdrException.mkInput("Give the key a name: what it is for.")
        if (keyName.length > AKEY.maxNameLength) {
            throw KdrException.mkInput("A key's name may be at most ${AKEY.maxNameLength} characters.")
        }
        val keyAccess = access ?: AKEY.read
        if (keyAccess !in AKEY.accesses) {
            throw KdrException.mkInput("A key's access is '${AKEY.read}' or '${AKEY.full}', not '$keyAccess'.")
        }
        // A sandbox's users act with the standing of the person's users outside it (`SandboxAccess`), which a key with
        // nobody signed in behind it would have no way to ask about.
        if (isSandboxClient(owner.client)) {
            throw KdrException.mkInput("A sandbox's users cannot hold API keys; make the key in the client itself.")
        }
        if (!owner.enabled) throw KdrException.mkInput("User ${owner.userId} is disabled, so a key for them would not work.")
        val now = cxt.now()
        val maxDays = ApiKeyLifetime.maxDays(cxt)
        val lifetime = days ?: ApiKeyLifetime.days(cxt)
        if (lifetime < 1) throw KdrException.mkInput("A key must last at least one day.")
        val expiry = ApiKeyRules.newExpiry(now, lifetime)
        ApiKeyRules.capProblem(expiry, now, maxDays)?.let { throw KdrException.mkInput(it) }
        if (liveCount(cxt, keysOf(cxt, owner.client, owner.userId)) >= AKEY.maxLiveKeys) {
            throw KdrException.mkInput(
                "User ${owner.userId} already holds ${AKEY.maxLiveKeys} keys that work; revoke one before making another.",
            )
        }
        val keyId = RandomUtil.secureBytes(keyIdBytes).base64Encode()
        val secret = RandomUtil.secureBytes(secretBytes).base64Encode()
        val row = mutableMapOf<String, Any?>(
            AKEY.keyId to keyId,
            AKEY.name to keyName,
            AKEY.access to keyAccess,
            APK.secretHash to hashOf(secret),
            AKEY.expiresAt to expiry,
            // The owner's, not the maker's: prepForStdExecute would otherwise stamp the signed-in caller's own.
            PF.userId to owner.userId,
            PF.client to owner.client,
        )
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = table(cxt)
        SqlTopicUtil.prepForStdExecute(cxt, table, row)
        sqlCxt.sqlDb.withSession(cxt) { sqlCxt.sqlDb.executeStatement(cxt, SqlTopicUtil.mkTableInsertStmt(sqlCxt, table), row) }
        // Counted again, now the key is written: two creates at once each counted the other's key as not yet there. The
        // later one -- this one, if both see too many -- is taken back rather than leaving the user over the limit.
        if (liveCount(cxt, keysOf(cxt, owner.client, owner.userId)) > AKEY.maxLiveKeys) {
            queryKey(cxt, keyId, forWrite = true)?.let { made ->
                val undone = made.toMutableMap()
                undone[AKEY.revokedAt] = cxt.now()
                undone[AKEY.revokedBy] = cxt.userProfile.userId
                write(cxt, undone, enabled = false)
            }
            throw KdrException.mkInput(
                "User ${owner.userId} already holds ${AKEY.maxLiveKeys} keys that work; revoke one before making another.",
            )
        }
        LogAuth.info(cxt) {
            "User ${cxt.userProfile.userId} made API key $keyId ('$keyName', $keyAccess) for user ${owner.userId}, " +
                "expiring ${expiry}."
        }
        return ApiKeyCreated(infoOf(row), "${AKEY.prefix}${keyId}_$secret")
    }

    /**
     * Extends the key [row] by [days] or [months] -- exactly one ([ApiKeyRules.extensionProblem]) -- from its expiry,
     * or from now when that has passed, held to [ApiKeyLifetime.maxDays] from today. A revoked key stays revoked.
     */
    fun extend(cxt: KdrCxt, row: Map<String, Any?>, days: Int?, months: Int?): ApiKeyInfo {
        val info = infoOf(row)
        if (info.revokedAt != null) throw KdrException.mkInput("API key ${info.keyId} is revoked; make a new one instead.")
        ApiKeyRules.extensionProblem(days, months)?.let { throw KdrException.mkInput(it) }
        val now = cxt.now()
        val expiry = ApiKeyRules.extendedExpiry(info.expiresAt, now, days, months)
        ApiKeyRules.capProblem(expiry, now, ApiKeyLifetime.maxDays(cxt))?.let { throw KdrException.mkInput(it) }
        val updated = row.toMutableMap()
        updated[AKEY.expiresAt] = expiry
        write(cxt, updated, enabled = true)
        LogAuth.info(cxt) { "User ${cxt.userProfile.userId} extended API key ${info.keyId} of user ${info.userId} to $expiry." }
        return infoOf(updated)
    }

    /**
     * Sets the key [row]'s access to [access], read-only or full. It bites on the key's next request, on every node
     * once its cache has heard, as a revocation does. A revoked key is not changed; one already as asked is answered
     * as it is, unwritten.
     */
    fun setAccess(cxt: KdrCxt, row: Map<String, Any?>, access: String): ApiKeyInfo {
        val info = infoOf(row)
        if (info.revokedAt != null) throw KdrException.mkInput("API key ${info.keyId} is revoked; make a new one instead.")
        if (access !in AKEY.accesses) {
            throw KdrException.mkInput("A key's access is '${AKEY.read}' or '${AKEY.full}', not '$access'.")
        }
        if (access == info.access) return info
        val updated = row.toMutableMap()
        updated[AKEY.access] = access
        write(cxt, updated, enabled = true)
        LogAuth.info(cxt) { "User ${cxt.userProfile.userId} set API key ${info.keyId} of user ${info.userId} to '$access' access." }
        return infoOf(updated)
    }

    /** Revokes the key [row] at once, for good: it is disabled, so no node honors it once its cache has heard. */
    fun revoke(cxt: KdrCxt, row: Map<String, Any?>): ApiKeyInfo {
        val info = infoOf(row)
        if (info.revokedAt != null) return info
        val updated = row.toMutableMap()
        updated[AKEY.revokedAt] = cxt.now()
        updated[AKEY.revokedBy] = cxt.userProfile.userId
        write(cxt, updated, enabled = false)
        lastUseWritten.remove(info.keyId)
        LogAuth.info(cxt) { "User ${cxt.userProfile.userId} revoked API key ${info.keyId} of user ${info.userId}." }
        return infoOf(updated)
    }

    /** Writes [data], a row read from the table and changed, keeping its owner and with [enabled] as given. */
    private fun write(cxt: KdrCxt, data: MutableMap<String, Any?>, enabled: Boolean) {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
        val table = table(cxt)
        SqlTopicUtil.prepForStdExecute(cxt, table, data)
        // prepForStdExecute enables every row it prepares, which would undo a revocation.
        data[PF.enabled] = enabled
        sqlCxt.sqlDb.withSession(cxt) { sqlCxt.sqlDb.executeStatement(cxt, SqlTopicUtil.mkTableUpdateStmt(sqlCxt, table), data) }
    }

    // --- authenticating ------------------------------------------------------------------------------------------

    /**
     * Authenticates the request in [cxt] by [presented], the key a program sent: binds the owner's acting profile and
     * records the key on the context ([KdrCxt.apiKey]), or refuses with one 401 whatever was wrong -- no such key, a
     * wrong secret, revoked, expired, or an owner who is disabled or whose client this node does not carry -- since
     * saying which would tell a guesser how close they came. A run of failures from one [sourceAddress] is refused
     * with a 429 until its window passes.
     */
    fun authenticate(cxt: KdrCxt, presented: String, sourceAddress: String?) {
        val refuse = refuser(cxt, sourceAddress, "The API key is not valid.")
        val (keyId, secret) = parseKey(presented) ?: refuse("it is not shaped as a key")
        val row = queryKeyBounded(cxt, keyId) ?: refuse("no key $keyId")
        // The hash first, so a revoked or expired key answers no faster than a wrong one.
        val matches = constantTimeEquals(hashOf(secret), row[APK.secretHash].toOptStr().orEmpty())
        if (!matches) refuse("the secret of key $keyId does not match")
        bindKey(cxt, row, scope = null, viaToken = false, refuse)
    }

    /**
     * Authenticates the request in [cxt] by an access token already verified (issue #1403) -- its signature, issuer,
     * audience and expiry, which are [AccessTokenService]'s to check -- that names the key [keyId], acting as [userId]
     * of [client] with [scope]. The key must still work and still be that user's, and the owner must still be enabled,
     * as for the key itself: so revoking a key, or disabling its owner, ends its tokens here at once, though a service
     * verifying a token with the published keys alone honors it until it expires. [refuse] says why it is not.
     */
    fun authenticateToken(cxt: KdrCxt, keyId: String, userId: Long, client: String, scope: String, refuse: (String) -> Nothing) {
        val row = queryKeyBounded(cxt, keyId) ?: refuse("no key $keyId")
        if (row[PF.userId].toOptLong() != userId || row[PF.client] != client) refuse("key $keyId is not that user's")
        bindKey(cxt, row, scope, viaToken = true, refuse)
    }

    /**
     * A refusal of a credential from [sourceAddress]: one 401 saying [message], whatever was wrong, since saying which
     * would tell a guesser how close they came. Why is logged. A run of them from one address is refused with a 429
     * until its window passes.
     */
    fun refuser(cxt: KdrCxt, sourceAddress: String?, message: String): (String) -> Nothing = { reason ->
        LogAuth.info(cxt) { "$message Refused: $reason." }
        if (!failures.allow("apikey:${sourceAddress ?: "unknown"}", failuresAllowed, failureWindowMs, cxt.now().toEpochMilliseconds())) {
            throw KdrException("Too many invalid API keys from this address; try again later.", code = EXC.tooManyRequests)
        }
        throw KdrException(message, code = EXC.authNeeded)
    }

    /**
     * The key [keyId], read for a credential presented. A key this node's cache does not hold -- revoked, or one nobody
     * issued -- costs a database read. Those are bounded node-wide, whatever address they claim to come from, so a flood
     * of made-up keys cannot turn into a flood of queries; keys the cache holds go on working through it.
     */
    private fun queryKeyBounded(cxt: KdrCxt, keyId: String): Map<String, Any?>? = queryKey(cxt, keyId, onMiss = {
        if (!failures.allow(missKey, missesAllowed, failureWindowMs, cxt.now().toEpochMilliseconds())) {
            throw KdrException("Too many unknown API keys; try again later.", code = EXC.tooManyRequests)
        }
    })

    /**
     * Binds the owner of the key [row] to [cxt] and records the key on it, once the key is found to work and its owner
     * to be enabled and in a client this node carries; [refuse] otherwise. A token's [scope] can only narrow the key's
     * access, never widen it, so a key made read-only since its token was issued is read-only through the token too.
     */
    private fun bindKey(cxt: KdrCxt, row: Map<String, Any?>, scope: String?, viaToken: Boolean, refuse: (String) -> Nothing) {
        val info = infoOf(row)
        if (row[PF.enabled] != true || !info.isUsable(cxt.now())) refuse("key ${info.keyId} is revoked or expired")
        val owner = UserService.get(cxt).queryByUserId(cxt, info.userId) ?: refuse("the owner of key ${info.keyId} is gone")
        if (!owner.enabled || owner.client != info.client) refuse("the owner of key ${info.keyId} is disabled or moved")
        if (ClientService.getOrNull(cxt)?.isPresent(owner.client) == false) refuse("client ${owner.client} is not carried here")
        val access = if (scope == AKEY.read) AKEY.read else info.access
        cxt.bindToUserProfile(owner.toActingProfile(cxt))
        cxt.apiKey = ApiKeyAccess(info.keyId, access, info.lastUsedAt, info.expiresAt, viaToken)
    }

    /**
     * Stamps the [AKEY.lastUsedAt] of the key [access] names when it is an hour or more old -- on this node's memory as
     * well as the row's, so a key busy on several nodes writes about once an hour on each, not on every request. Called
     * by the dispatcher once a keyed request has been answered without an error, so a refused call is not a use. The
     * stamp is the only column written and `updatedAt` is left as it is: it is bookkeeping, not a change to the key, so
     * the cache has nothing to reload for it. A failure is logged and nothing else happens.
     */
    fun noteUse(cxt: KdrCxt, access: ApiKeyAccess) {
        val nowMs = cxt.now().toEpochMilliseconds()
        val stamped = maxOf(access.lastUsedAt?.toEpochMilliseconds() ?: 0L, lastUseWritten[access.keyId] ?: 0L)
        if (nowMs - stamped < lastUseIntervalMs) return
        lastUseWritten[access.keyId] = nowMs
        try {
            val sqlCxt = SqlTopicService.mkSqlCxt(cxt, authTopic)
            val stmt = SqlStmtUtil.prepareSql(
                sqlCxt, "uAuthApiKeysLastUsed", table(cxt).columns,
                "update t:${UT.authApiKeys} set c:${AKEY.lastUsedAt} = :${AKEY.lastUsedAt} where c:${AKEY.keyId} = :${AKEY.keyId}",
            )
            sqlCxt.sqlDb.withSession(cxt) {
                sqlCxt.sqlDb.executeStatement(cxt, stmt, mapOf(AKEY.lastUsedAt to cxt.now(), AKEY.keyId to access.keyId))
            }
        } catch (e: Exception) {
            LogAuth.warn(cxt, "Could not record the use of API key ${access.keyId}: ${e.message}")
        }
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "ApiKeyService"

        /** Random bytes in a key's public id: 9, which base64url writes as [AKEY.keyIdLength] characters. */
        const val keyIdBytes = 9

        /** Random bytes in a key's secret: 256 bits. */
        const val secretBytes = 32

        /** Failed keys allowed from one address within [failureWindowMs]. */
        const val failuresAllowed = 30

        /** The failure window: 15 minutes. */
        const val failureWindowMs = 15L * 60 * 1000

        /**
         * Keys this node's cache does not hold that may be looked up in the database within [failureWindowMs], across
         * every caller: generous for revoked keys still in use, and a bound on what a flood of made-up keys costs.
         */
        const val missesAllowed = 600

        /** The rate limiter's key for [missesAllowed]. */
        private const val missKey = "apikey-miss"

        /** How stale a key's last-use stamp may get before a use writes a new one: an hour. */
        const val lastUseIntervalMs = 60L * 60 * 1000

        /** The bind parameter naming the one user a listing is narrowed to. */
        private const val listUserParam = "listUserId"

        fun get(cxt: KdrCxt): ApiKeyService = getOrNull(cxt) ?: throw KdrException("The $serviceName is not available on this node.")

        fun getOrNull(cxt: KdrCxt): ApiKeyService? = cxt.instanceConfig.get(serviceName) as? ApiKeyService

        /** The SHA-256 of a key's secret, in hex: what [APK.secretHash] holds. */
        fun hashOf(secret: String): String = secret.toByteArray(Charsets.UTF_8).sha256Hex()

        /**
         * The key id and secret in [presented], or null when it is not shaped as a key: [AKEY.prefix], a
         * [AKEY.keyIdLength]-character id, `_`, and a secret. Read by position rather than split on `_`, which
         * base64url uses too.
         */
        fun parseKey(presented: String): Pair<String, String>? {
            val text = presented.trim()
            val idEnd = AKEY.prefix.length + AKEY.keyIdLength
            if (!text.startsWith(AKEY.prefix) || text.length <= idEnd + 1 || text[idEnd] != '_') return null
            val secret = text.substring(idEnd + 1)
            if (secret.length > maxSecretLength) return null
            return text.substring(AKEY.prefix.length, idEnd) to secret
        }

        /** Longer than any secret this service makes, so an absurd header is refused before it is hashed. */
        private const val maxSecretLength = 128

        /**
         * The key in an `Authorization` header [value], or null when the header carries none -- absent, another scheme
         * (a site behind HTTP basic authentication sends `Basic` on every request), or a bearer token that is not ours.
         */
        fun bearerKeyOf(value: String?): String? {
            val v = value?.trim() ?: return null
            if (!v.regionMatches(0, AKEY.bearerScheme, 0, AKEY.bearerScheme.length, ignoreCase = true)) return null
            return v.substring(AKEY.bearerScheme.length).trim().takeIf { it.startsWith(AKEY.prefix) }
        }
    }
}
