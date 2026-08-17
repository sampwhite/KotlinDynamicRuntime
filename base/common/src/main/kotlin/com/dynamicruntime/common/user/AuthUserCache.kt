package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.sql.cache.SqlCacheIndex
import com.dynamicruntime.common.sql.cache.SqlCacheParams
import com.dynamicruntime.common.sql.cache.SqlTableCache
import com.dynamicruntime.common.sql.cache.SqlTableCacheService
import com.dynamicruntime.common.util.toOptStr

/**
 * The in-memory cache of the `AuthUsers` table -- the first consumer of the table-cache subsystem, and the
 * one that most obviously wants it: every gated request resolves the acting user's row before it does
 * anything else (`refreshActingRoles`), and the login flows resolve one or two more.
 *
 * **The cache holds the raw stored row map, not an extracted [AuthUserRow]** -- each consumer extracts its
 * own row per read (`UserService.cachedUser`). Two reasons, both learned the hard way:
 *
 *  - [AuthUserRow] is mutable and callers routinely edit one and write it back, so a cached instance would
 *    have to be defensively copied on every lookup anyway; with the raw map, extraction *is* the copy.
 *  - Extraction scrubs the password out of the row's retained `data`, which is right for anything handed to
 *    request code but wrong for the cache's own copy: a row re-extracted from a scrubbed map would lose
 *    [AuthUserRow.encodedPassword], silently breaking password login on a cache hit. The raw map keeps full
 *    fidelity, exactly as a fresh SQL read would.
 *
 * Three lookups are cached, matching the three the table has unique indexes for: by `userId` (the primary
 * key) and by `username` / `primaryId` (declared here as unique cache indexes, keyed off the raw columns).
 *
 * **A miss always falls back to SQL**, which is what keeps the semantics identical rather than merely
 * similar. In particular a *disabled* user is deliberately not in the cache -- the initial load takes enabled
 * rows only, and a row that is disabled later becomes a tombstone -- while `UserService.queryOne` is
 * documented to return disabled rows. So looking one up costs a query, exactly as it always did.
 */
object AuthUserCache {
    /** Builds the cache parameters; see the class doc for why the payload is the raw row map. */
    fun params(): SqlCacheParams<Map<String, Any?>> = SqlCacheParams(
        topic = authTopic,
        tableName = UT.authUsers,
        extract = { _, data -> data },
        indexes = listOf(
            // Both are unique in the database too, so a duplicate here means the database let one through --
            // which the cache logs as an error rather than quietly answering with one of the two.
            SqlCacheIndex(AU.username, unique = true) { it[AU.username].toOptStr() },
            SqlCacheIndex(AU.primaryId, unique = true) { it[AU.primaryId].toOptStr() },
        ),
    )

    /** Registers the cache with the running service, or returns null when there is none. */
    fun register(cxt: KdrCxt): SqlTableCache<Map<String, Any?>>? = SqlTableCacheService.registerCache(cxt, params())
}
