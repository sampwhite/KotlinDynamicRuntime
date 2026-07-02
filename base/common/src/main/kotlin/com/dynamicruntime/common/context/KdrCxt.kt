package com.dynamicruntime.common.context

import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The universal context object for the runtime. It is passed down a call stack
 * as an explicit alternative to scoped / thread-local variables: from it, you can
 * reach the application configuration, the acting user, the bound client
 * account, the schema store, and free-form association maps for handing extra
 * information to implementers further down the stack.
 *
 * Every context is named and carries a parent context path (the chain of parent
 * logging ids), which is used in logging and debugging. A context is never handed
 * directly to another thread; instead a sub context is created with
 * [mkSubContext], which clones the propagated data ([locals]) so the two threads
 * cannot pollute each other.
 *
 * Inspired by `DnCxt` from the prior-art dynamicruntime project, modernized to
 * Kotlin. Notably, the old `shard` concept is intentionally not carried over, and
 * instance config / user profile / schema store are placeholder stubs for now.
 */
class KdrCxt(
    /** Short name for this context, used as the prefix of its logging id. */
    cxtName: String,
    instanceConfig: KdrInstanceConfig? = null,
    parentCxt: KdrCxt? = null,
    /** The current acting user for this context. May be reassigned. */
    var userProfile: UserProfile = UserProfile.systemUser(),
    /**
     * The client account this context is bound to. Defaults to the acting user's
     * account. It can be set to a different account -- for users with admin
     * rights editing across client accounts, or for background jobs with
     * cross-account scope -- typically by creating a sub context with
     * [mkSubContext] and supplying the alternate account.
     */
    var account: String = userProfile.account,
) {
    /** Configuration of the running instance/application. */
    val instanceConfig: KdrInstanceConfig = instanceConfig ?: KdrInstanceConfig.codeTest()

    /** Logging ids inherited from parent contexts, oldest first. */
    val parentLoggingIds: List<String> =
        if (parentCxt == null) emptyList() else parentCxt.parentLoggingIds + parentCxt.loggingId

    /** Unique-ish id identifying this context in logging and debug output. */
    val loggingId: String = cxtName + this.instanceConfig.nextLoggingId()

    /**
     * Objects attached to this context only and NOT propagated to sub contexts.
     * Plays the role usually played by thread-local storage, but travels with the
     * context. (e.g., SQL transaction sessions belong here.)
     */
    val session: MutableMap<String, Any?> = mutableMapOf()

    /**
     * Objects attached to this context and carried down to sub contexts. Assumed
     * to be accessed by a single thread; [mkSubContext] clones this map so a
     * worker thread gets its own copy. The interior values are assumed to be
     * immutable or thread-safe (the clone is shallow). A typical use is caching the
     * results of data gathering and computation.
     */
    val locals: MutableMap<String, Any?> = mutableMapOf()

    /** Used by tests to do time travel; offsets [now]. */
    var nowTimeOffsetInSeconds: Int = 0

    /** When this context was created. */
    val creationDate: Instant = Clock.System.now()

    /** Nano time at creation; used to time requests. */
    val nanoTime: Long = System.nanoTime()

    /**
     * Originating IP address, set only when relevant to processing the request.
     * Useful for per-address aggregation/limiting and for logging.
     */
    var forwardedFor: String? = null

    /**
     * The request being processed, or null when this context is not handling one (startup, background
     * jobs, tests). Set when an endpoint invocation begins and inherited by sub contexts. The mutable
     * response accumulator will be a separate field added when endpoint execution is built.
     */
    var request: KdrRequest? = null

    /** Cached read-only schema store; lazily populated via [getSchema]. */
    var schemaStore: KdrSchemaStore? = null

    /** Creates a sub context that inherits this context's bound [account]. */
    fun mkSubContext(subCxtName: String): KdrCxt = mkSubContext(subCxtName, account)

    /**
     * Creates a sub context bound to the supplied [account] instead of inheriting
     * this context's account. Use this when the acting user's account must differ
     * from the account being operated on (admin cross-account edits, cross-account
     * background jobs). The sub context clones the propagated [locals] and copies
     * the schema store and originating address, but does NOT carry over [session].
     */
    fun mkSubContext(subCxtName: String, account: String): KdrCxt {
        val sub = KdrCxt(subCxtName, instanceConfig, this, userProfile, account)
        sub.locals.putAll(locals)
        sub.schemaStore = schemaStore
        sub.forwardedFor = forwardedFor
        sub.request = request // a sub context is part of the same request
        return sub
    }

    /** Returns the schema store, lazily creating and caching it on first access. */
    fun getSchema(): KdrSchemaStore {
        val existing = schemaStore
        if (existing != null) {
            return existing
        }
        val created = KdrSchemaStore.get(this)
        schemaStore = created
        return created
    }

    /** The full context path: parent logging ids followed by this one, ":"-joined. */
    fun cxtPath(): String = (parentLoggingIds + loggingId).joinToString(":")

    /** Logging label combining the logging id with the acting user (or %sys). */
    fun logInfo(): String {
        val authId = userProfile.authId
        return if (authId != null) "$loggingId($authId)" else "$loggingId%sys"
    }

    /** Duration since this context was created, in milliseconds. */
    fun durationMs(): Double = (System.nanoTime() - nanoTime) / 1_000_000.0

    /** Current time, adjusted by [nowTimeOffsetInSeconds] for test time travel. */
    fun now(): Instant = Clock.System.now() + nowTimeOffsetInSeconds.seconds

    companion object {
        /** Creates a simple top-level context with placeholder config and user. */
        fun mkSimpleCxt(cxtName: String): KdrCxt = KdrCxt(cxtName)
    }

    fun getEnvVar(key: String): String? {
        return instanceConfig.getEnvVar(key)
    }
}
