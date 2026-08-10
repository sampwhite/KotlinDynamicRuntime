package com.dynamicruntime.common.context

/**
 * Which rows a caller may read (issue #225) -- the *scope* half of an authority, the privilege level being the
 * other half. It narrows in widths: an ordinary user sees rows they own, an administrator sees their whole
 * client, and an administrator with `allClients` sees everything.
 *
 * **A value rather than a pair of nullable arguments**, because the constraint has always been more than one
 * field and is about to be one more. `listUsers(cxt, search, limit, client, org)` is the signature that gets
 * called with two nullable strings the wrong way round, and it spreads: once ordinary endpoints scope their
 * reads too, this travels through most of them. Adding a dimension should be a field here, not an edit at
 * every call site.
 *
 * The fields sit on the owner axis `KdrCxt` already draws -- `client`/`userId` answer *"who owns the row?"* as
 * distinct from `userProfile`, which answers *"who is acting on it?"*. A null field is **no constraint on that
 * dimension**, so [unrestricted] (every field null) reads everything.
 *
 * **Where `org` will go.** A primary organization within a client is a coming feature: it becomes another
 * field here, and the widths become own-user < own-org < own-client < all-clients. It is deliberately not
 * declared yet -- a dimension nothing populates is one somebody half-uses. Three things are already decided:
 *
 *  - A row whose org is null **belongs to the client rather than to any organization**, and stays visible to
 *    everyone in that client. The lenient reading, chosen so that adopting orgs does not hide all the content
 *    that predates them.
 *  - A user's assignment is held in `authUserData` and surfaced on [UserProfile] -- **no database column**,
 *    the same way roles are. So it travels with the acting caller, which is what lets a write stamp the org
 *    onto content without a lookup.
 *  - Consequently the two sides are not symmetric. *Content* carries a real org column and filters in SQL, as
 *    `client` does here. *Users* do not, so narrowing a user list to an org can only be done after the query,
 *    which makes [ReadScope.client] the last predicate keeping `limit` an exact cap on that table.
 */
class ReadScope(
    /** Confine to this client, or null for any client. */
    val client: String? = null,
    /** Confine to rows owned by this user, or null for any owner. */
    val userId: Long? = null,
) {
    /** Whether nothing is constrained -- the reach of an `allClients` administrator. */
    val isUnrestricted: Boolean get() = client == null && userId == null

    /**
     * A short, stable token naming this scope's *shape* (not its values), for composing prepared-statement
     * names. Statements are cached by name, so two shapes must not share one -- and two callers with the same
     * shape and different values must share, or the cache grows a statement per client.
     */
    val shapeKey: String
        get() = buildString {
            if (client != null) append("C")
            if (userId != null) append("U")
            if (isEmpty()) append("Any")
        }

    override fun toString(): String = "ReadScope(client=$client, userId=$userId)"

    companion object {
        /** No constraint at all. */
        val unrestricted: ReadScope = ReadScope()

        /** Everything owned by [client]. */
        fun ofClient(client: String): ReadScope = ReadScope(client = client)

        /** Only what [userId] owns -- an ordinary user's own reach. */
        fun ofUser(userId: Long): ReadScope = ReadScope(userId = userId)
    }
}
