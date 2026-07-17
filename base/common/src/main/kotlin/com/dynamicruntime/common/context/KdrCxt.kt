package com.dynamicruntime.common.context

import com.dynamicruntime.common.util.splitComma
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The universal context object for the runtime. It is passed down a call stack
 * as an explicit alternative to scoped / thread-local variables: from it, you can
 * reach the application configuration, the acting user, the bound owner
 * ([account] / [userId]), the schema store, and free-form association maps for
 * handing extra information to implementers further down the stack.
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
     * The client account that *owns* the data this context is operating on. Defaults to the acting user's
     * account. This is distinct from [userProfile], which is who is *acting*: [account]/[userId] answer "who
     * owns the row?" (consulted by the account/user table features), while [userProfile] answers "who is
     * acting on the row?" (consulted to stamp `createdBy`/`updatedBy`). It can be pointed at a different
     * account -- for admins editing across client accounts, or background jobs with cross-account scope --
     * by creating a sub context with [mkSubContext] and supplying the alternate account, via
     * [bindToUserProfile], or by direct assignment when a request decides it is operating on another owner's
     * data.
     */
    var account: String = userProfile.account,
    /**
     * The numeric id of the user that *owns* the data this context is operating on (companion to [account];
     * see that field for the owner-vs-actor distinction). Defaults to the acting user's
     * [UserProfile.userId] and can likewise be reassigned when operating on another user's data.
     */
    var userId: Long = userProfile.userId,
) : KdrCxtBase {
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
     * Optional debug tag(s) for this request: a validated, comma-separated list of variable names supplied
     * via the off-contract `_debug` request key. When present it is prefixed onto every log message and can
     * gate diagnostic behavior (e.g., the sample endpoint's `explainInput`). Carried down to sub contexts.
     *
     * Test membership with [hasDebug], never `debug.contains(...)`: the latter is a substring match, so a check
     * for `foo` would fire on `_debug=foobar`.
     */
    var debug: String? = null

    /**
     * The client-supplied application id for this request (issue #105): the app plus its locale suffix, used
     * to select content. Null off a request or when the client sent none. Carried down to sub contexts.
     */
    var appId: String? = null

    /**
     * The client-supplied trace id for this request (issue #105): the frontend mints one per call, and it is
     * stamped onto every log line for the request (see [cxtPath]/[logInfo]) so a call can be followed from the
     * browser to the server. Null when the client sent none -- then [loggingId] alone identifies the request.
     * Carried down to sub contexts.
     */
    var traceId: String? = null

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
        val sub = KdrCxt(subCxtName, instanceConfig, this, userProfile, account, userId)
        sub.locals.putAll(locals)
        sub.schemaStore = schemaStore
        sub.forwardedFor = forwardedFor
        sub.debug = debug // debug tags travel with the request
        sub.appId = appId // request identity travels with the request...
        sub.traceId = traceId // ...so a sub context's log lines carry the same trace id
        sub.request = request // a sub context is part of the same request
        return sub
    }

    /**
     * Binds this context to an authenticated [userProfile]: makes it the acting user and resets the owning
     * [account] and [userId] to that user's own. Called when a request has been authenticated as acting on
     * behalf of a particular user. Afterward a request may point [account]/[userId] at a different owner
     * (e.g., when operating on data owned by another user) without changing who is acting ([userProfile]).
     */
    fun bindToUserProfile(userProfile: UserProfile) {
        this.userProfile = userProfile
        this.account = userProfile.account
        this.userId = userProfile.userId
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

    /**
     * The **full** context path: the client [traceId] (when present), then the whole chain of parent logging
     * ids, then this context's own. This is the rich form -- it grows with nesting, and deep executor-pool
     * work can make it long -- so it is meant for a structured sink (a future OpenSearch-style destination
     * that also carries the device id and more), **not** the console. The console log uses [logInfo], which is
     * deliberately minimal (this context only). Currently no sink consumes the full path; keep it that way for
     * the console when one is added.
     */
    fun cxtPath(): String = (listOfNotNull(traceId) + parentLoggingIds + loggingId).joinToString(":")

    /**
     * The **minimal** logging label for the console: this context's logging id with the acting user (or
     * %sys), prefixed with the client [traceId] when there is one -- so every line carries the id the frontend
     * also holds (one grep spans both tiers) without dragging the whole parent chain onto every line (see
     * [cxtPath]).
     */
    fun logInfo(): String {
        val trace = traceId?.let { "$it:" } ?: ""
        val authId = userProfile.authId
        return if (authId != null) "$trace$loggingId($authId)" else "$trace$loggingId%sys"
    }

    /**
     * Whether the request's [debug] tag lists [name] as one of its comma-separated words -- an **exact** word,
     * not a substring, so `_debug=explainInputFully` does not answer a check for `explainInput`. The way to
     * gate diagnostic behavior on a `_debug` tag; use this rather than `debug?.contains(name)`.
     */
    fun hasDebug(name: String): Boolean = debug?.splitComma()?.contains(name) == true

    /** Duration since this context was created, in milliseconds. */
    fun durationMs(): Double = (System.nanoTime() - nanoTime) / 1_000_000.0

    /** Current time, adjusted by [nowTimeOffsetInSeconds] for test time travel. */
    override fun now(): Instant = Clock.System.now() + nowTimeOffsetInSeconds.seconds

    companion object {
        /** Creates a simple top-level context with placeholder config and user. */
        fun mkSimpleCxt(cxtName: String): KdrCxt = KdrCxt(cxtName)

        /** Creates a simple top-level context bound to the given [instanceConfig] (e.g. a pre-boot config). */
        fun mkSimpleCxt(cxtName: String, instanceConfig: KdrInstanceConfig): KdrCxt =
            KdrCxt(cxtName, instanceConfig)
    }

    fun getEnvVar(key: String): String? {
        return instanceConfig.getEnvVar(key)
    }
}
