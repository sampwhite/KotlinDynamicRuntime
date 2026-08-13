package com.dynamicruntime.common.intern

/**
 * A value with a canonical string form, so that one instance can stand for every occurrence of that form
 * (issue #280).
 *
 * The motivating case is an id. An id arrives as text, carries structure worth extracting once (a kind, a
 * client, a counter), and never changes afterward — so parsing it repeatedly is waste, and holding many equal
 * copies of it is waste twice over. Interning gives back the single instance for a form, which makes `===` a
 * legitimate identity test and makes the value a good hash key: its hash is computed once and its equality
 * check usually settles on the reference comparison.
 *
 * Lives in the kernel so a value type can implement it on either side of the wire. The **cache** does not:
 * interning is only sound where one process holds every extant value of a kind, which is a claim a backend can
 * make and a browser generally cannot.
 *
 * ### The contract
 *
 * - [toInternString] is *canonical*: two values that are equal return the same string, and two that are not,
 *   never do. It is the cache key, so a form that two different values can produce silently merges them.
 * - It is *stable*: the same instance returns the same string for its whole life. Implementations are expected
 *   to be immutable, which is the easy way to guarantee this.
 * - It *round-trips*: parsing the returned string yields a value equal to this one. Nothing here can enforce
 *   that — a parse is a static operation an interface cannot demand — so it is stated rather than checked.
 *   `InternCache.getOrIntern` does check the half it can see, that a freshly built value spells itself back as
 *   the key it was asked for.
 *
 * Where a value's text form is *already* what it is stored and transmitted as, [toInternString] returns that
 * text rather than inventing a second spelling. The point is that the key on the wire and the key in the cache
 * are the same string.
 */
interface Internable {
    /** This value's canonical text form — the key it is interned under. See the contract above. */
    fun toInternString(): String
}
