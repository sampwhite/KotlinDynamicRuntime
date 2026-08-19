package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.intern.InternCache
import com.dynamicruntime.common.startup.ServiceInitializer

/**
 * What the two Gedra services share (issue #310).
 *
 * `GedraDataService` and the config service that will join it are **not** subclasses of this, and that is the
 * point: they store different things in different tables under different rules, and the one thing they
 * genuinely have in common is the identity space. Inheritance would put that shared piece behind a promise
 * that the rest of the two services resemble each other, which they do not. A collaborator they both hold
 * says only what is true.
 *
 * Today the shared piece is exactly one thing, [gedraIds]. That is a thin service, and a thin service is the
 * right size for it: the alternative is each service holding its own cache, which is the arrangement #287
 * specifically decided against — one [GedraId] for everybody, so common code that edits both data and config
 * has one cache to consult rather than a choice to get wrong.
 */
class GedraService : ServiceInitializer {
    override val serviceName: String = GedraService.serviceName

    /**
     * The single intern cache for every [GedraId], data and config alike.
     *
     * It is a field of an instance-scoped service rather than a singleton, which [InternCache] requires of its
     * holders: a cache reachable from a companion would be shared by every instance in the process, and a test
     * suite boots many.
     *
     * **The existence property is not available yet, and code here must not assume it.** A miss only means
     * "no such gedra" for a cache that holds *every* extant id, and nothing loads them exhaustively — data ids
     * are interned as they are created and read, which is enough for `===` and for cheap keys and nothing more.
     * So use [readId], never `gedraIds.gedraId(...)`, until something populates this exhaustively. The memory
     * cache of gedra data that #310 anticipates is what would make the stronger reading honest; whoever builds
     * it should come back and say so here. See deferred-work.md#when-gedra-data-is-held-in-a-memory-cache.
     */
    val gedraIds: InternCache<GedraId> = InternCache("gedraIds")

    /** The shared instance for [id]'s text form. The caller must use the result and discard its own. */
    fun intern(id: GedraId): GedraId = gedraIds.intern(id)

    /**
     * [fullId] as an id, shared with every other holder of the same text.
     *
     * A miss parses rather than failing, so this says nothing about whether the gedra exists — see the note on
     * [gedraIds]. A caller asking whether something is *there* has to ask storage.
     */
    fun readId(fullId: String): GedraId = gedraIds.readGedraId(fullId)

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "GedraService"

        /** The service; throws naming it on a node that does not run it. */
        fun get(cxt: KdrCxt): GedraService = cxt.instanceConfig.get(serviceName) as? GedraService
            ?: throw KdrException("The $serviceName is not available on this node.")
    }
}
