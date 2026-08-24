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
 * **The organization width.** With [org] the widths run own-user < own-org < own-client < all-clients. Three
 * things about it are worth knowing before using this type:
 *
 *  - A row whose org is null **belongs to the client rather than to any organization**, and stays visible to
 *    everyone in that client -- see [admitsOrg]. The lenient reading, chosen so that adopting organizations
 *    does not hide all the content that predates them.
 *  - A *user's* assignment is held in `authUserData` and surfaced on [UserProfile] -- **no database column**,
 *    the same way roles are. So it travels with the acting caller, which is what lets a write stamp the
 *    organization onto content without a lookup.
 *  - Consequently the two sides are not symmetric. *Content* carries a real org column
 *    (`TableBuilder.forOrg`) and filters in SQL, as `client` does, via `SqlScopeUtil`. *Users* do not, so
 *    narrowing a user list to an organization can only be done after the query -- which is why
 *    `UserService.listUsers` has to name that dimension as one it filters itself, and why [client] is the last
 *    predicate keeping `limit` an exact cap on that one table.
 */
class ReadScope(
    /** Confine to this client, or null for any client. */
    val client: String? = null,
    /**
     * Confine to this organization within [client], or null for any organization. A row whose own org is null
     * belongs to the client rather than to any organization and stays **visible regardless** -- see
     * [admitsOrg]; that is the lenient rule, chosen so adopting organizations does not hide the content that
     * predates them.
     */
    val org: String? = null,
    /** Confine to rows owned by this user, or null for any owner. */
    val userId: Long? = null,
) {
    /** Whether nothing is constrained -- the reach of an `allClients` administrator. */
    val isUnrestricted: Boolean get() = client == null && org == null && userId == null

    /**
     * Whether a row whose organization is [rowOrg] is admitted. An unconfined scope admits everything; a
     * confined one admits its own organization **and** rows with no organization at all.
     *
     * The second half is the whole of the lenient decision. Strict matching would make every row written
     * before a client adopted organizations vanish the moment anybody was given a primary one -- an adoption
     * cliff that looks exactly like data loss.
     *
     * The cost is that an organization narrows a view without ever sealing it, so it is not a confidentiality
     * boundary: see deferred-work.md#when-an-organization-has-to-hide-content-not-just-narrow-it before
     * anybody relies on one to keep content apart.
     */
    fun admitsOrg(rowOrg: String?): Boolean = org == null || rowOrg == null || rowOrg == org

    /**
     * Whether a *user* row with this owner triple falls within the scope -- the per-row admission an
     * administrator's user reads apply (issues #225, #411).
     *
     * The one predicate `UserService.queryAdministrableUser` (a by-id read) and the brute-force cache search
     * (a listing) both use, so the two cannot come to disagree about what a scope admits. A user is the case
     * the caching skill's "a by-id read may check scope per row" rule was written for: unlike content, a user
     * carries no `org` column, so [org] cannot be an SQL predicate anyway (see the class doc) -- the scope is
     * only ever checked a row at a time.
     */
    fun admitsUserRow(rowClient: String, rowOrg: String?, rowUserId: Long): Boolean {
        if (client != null && rowClient != client) return false
        if (!admitsOrg(rowOrg)) return false
        if (userId != null && rowUserId != userId) return false
        return true
    }

    /**
     * A short, stable token naming this scope's *shape* (not its values), for composing prepared-statement
     * names. Statements are cached by name, so two shapes must not share one -- and two callers with the same
     * shape and different values must share, or the cache grows a statement per client.
     */
    val shapeKey: String
        get() = buildString {
            if (client != null) append("C")
            if (org != null) append("O")
            if (userId != null) append("U")
            if (isEmpty()) append("Any")
        }

    override fun toString(): String = "ReadScope(client=$client, org=$org, userId=$userId)"

    companion object {
        /** No constraint at all. */
        val unrestricted: ReadScope = ReadScope()

        /** Everything owned by [client]. */
        fun ofClient(client: String): ReadScope = ReadScope(client = client)

        /** [client], narrowed to one organization within it (plus that client's org-less rows). */
        fun ofOrg(client: String, org: String): ReadScope = ReadScope(client = client, org = org)

        /** Only what [userId] owns -- an ordinary user's own reach. */
        fun ofUser(userId: Long): ReadScope = ReadScope(userId = userId)
    }
}
