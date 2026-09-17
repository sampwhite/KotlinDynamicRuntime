package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.sql.cache.SqlCacheIndex
import com.dynamicruntime.common.sql.cache.SqlCacheParams
import com.dynamicruntime.common.sql.cache.SqlTableCache
import com.dynamicruntime.common.sql.cache.SqlTableCacheService
import com.dynamicruntime.common.startup.PRI
import com.dynamicruntime.common.util.toOptStr

/**
 * The in-memory cache of `AuthIdentities` (issue #747), beside [AuthUserCache] and for the same reason: a
 * login resolves an address to an identity, and **every extraction of a user row reads its identity's
 * address** (`AuthUserRow.primaryId` is derived, not stored), so the lookup must be a map hit. Holds the raw
 * row map like the user cache does, extracted per read. One unique index, on the normalized address.
 */
object AuthIdentityCache {
    fun params(): SqlCacheParams<Map<String, Any?>> = SqlCacheParams(
        topic = authTopic,
        tableName = UT.authIdentities,
        extract = { _, data -> data },
        indexes = listOf(
            SqlCacheIndex(AI.primaryId, unique = true) { it[AI.primaryId].toOptStr() },
        ),
        // Refreshed before the user cache: a user row's extraction reads its identity's address, so a fresh
        // user must never be ahead of its identity.
        priority = PRI.standard - 1,
    )

    /** Registers the cache with the running service, or returns null when there is none. */
    fun register(cxt: KdrCxt): SqlTableCache<Map<String, Any?>>? = SqlTableCacheService.registerCache(cxt, params())
}
