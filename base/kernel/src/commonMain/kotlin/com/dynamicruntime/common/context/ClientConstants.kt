package com.dynamicruntime.common.context

// Client constants, in the kernel (issue #78), so shared data classes like UserProfile can reference them
// from KMP code. Per the code guide: lowerCamelCase `const val`s in an upper-case acronym object, always
// referenced qualified (`CL.hub`), never wildcard-imported.

/** Client-related constants. */
@Suppress("ConstPropertyName")
object CL {
    /**
     * The deployment's own client: the one internal activity acts in when nothing has said otherwise.
     *
     * It was called `local` until issue #343, and the rename is worth the note. `CL.local` and `ENV.local`
     * were the same string meaning unrelated things, and a client declaring the environments it is enabled in
     * puts the two side by side constantly. Of the pair the client was the newcomer -- "local" for a
     * development environment is universal vocabulary -- so the client is what moved.
     *
     * `hub` names the position rather than the purpose. What this client is *for* is exactly what is still
     * being learned, so a name describing the job would risk going stale, while a name for where it sits stays
     * true whatever it comes to hold -- and it survives the future where several internal clients exist, since
     * one of them is still the hub. See `client-definition.md`.
     */
    const val hub = "hub"

    /** The client every self-registered user belongs to; also the anonymous profile's client. */
    const val public = "public"

    /**
     * The client an **env-authed** caller belongs to (issue #386): somebody who reached a node through an
     * authenticating edge, acting on the business's own behalf rather than any customer's.
     *
     * Named for the *position* -- "the house", as distinct from its customers -- which is the rule that
     * produced [hub]. [public] is guests, and `hub` suggests richer functionality than this. Preferred over
     * `staff` because the admin email domain may grow to a list (a contractor's, a client's), at which point
     * not everybody clearing the gate is staff, while they are all still acting for the house.
     *
     * **This client owns no rows.** An edge has no user store, so nothing is ever scoped to it, and a caller
     * carrying it has no database row behind them (see `UserProfile.isRowBacked`). It is a label for who is
     * acting, never a key to store anything under.
     */
    const val house = "house"

    // Placeholder id for the implicit system user.
    const val systemUserId = 0
}
