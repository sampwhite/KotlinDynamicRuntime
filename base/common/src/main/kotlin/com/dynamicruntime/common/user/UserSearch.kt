package com.dynamicruntime.common.user

import kotlin.time.Instant

/**
 * The brute-force search/sort over the user cache (issue #411).
 *
 * The whole feature is a scan of active users held in memory (`UserService.searchUsers`) rather than an SQL
 * query, so what a caller may search and sort on is not limited to what is a database column -- the public
 * name (username-or-email) and the update time are as ordinary here as the email. The tradeoff, chosen
 * deliberately with the issue, is that the cache holds **enabled rows only**, so this searches active users;
 * finding a disabled one to re-enable stays on the SQL listing (`UserService.listUsers`).
 *
 * **[userSearchFields] is the one extensibility point.** The issue's own framing is that each new user
 * attribute is "another search & sort combination"; a field added to that registry becomes both filterable
 * (when it declares [UserSearchField.textOf]) and sortable, with no new code path. [searchUserRows] below is
 * pure -- rows in, a page out -- so it is covered under Kotest without a server.
 */

/**
 * One searchable and/or sortable user attribute.
 *
 * Every field is **sortable** ([sortOf]); a field is additionally **text-searchable** when it declares
 * [textOf]. The update time is the one field that is sortable but not text-searchable -- it is filtered by a
 * date *range* instead (see [UserSearchCriteria.updatedAfter]), which is a different shape of filter and lives
 * apart from the uniform text one.
 */
class UserSearchField(
    /** The field's wire name -- one of the [USF] sort keys, and the key a text term arrives under. */
    val name: String,
    /**
     * The field's searchable texts, or null for a field that is not text-searchable (the update time). A term
     * matches the field when it matches **any** of them (OR) -- most fields return one, but [USF.name] returns
     * the real-world name *and* the public name (username), so the console's Name box finds an account by the
     * handle somebody logs in with as well as by the name it is shown under (restoring the pre-#411 behavior
     * the old single search box had; see `webapp/CLAUDE.md`).
     */
    val textsOf: ((AuthUserRow) -> List<String>)?,
    /** True: a case-insensitive **substring** match. False: a case-insensitive **exact** match (the client). */
    val substring: Boolean,
    /**
     * The value the field sorts by. Null sorts **last** regardless of direction -- an unnamed or dateless row
     * belongs at the end of a list ordered by that field, not surfaced at the top of a descending one.
     */
    val sortOf: (AuthUserRow) -> Comparable<*>?,
)

/**
 * The searchable/sortable user attributes (issue #411). Add an attribute here and it is searchable (if it
 * names [UserSearchField.textOf]) and sortable at once; the console offers the same set as columns to order
 * on. Text fields sort on a lower-cased value so the order matches the case-insensitive match.
 */
val userSearchFields: List<UserSearchField> = listOf(
    UserSearchField(USF.email, textsOf = { listOf(it.primaryId) }, substring = true, sortOf = { it.primaryId.lowercase() }),
    UserSearchField(
        USF.publicName, textsOf = { listOf(it.publicName()) }, substring = true, sortOf = { it.publicName().lowercase() },
    ),
    // Matches the real-world name OR the public name (username), so the console's Name box still finds an
    // account by its login handle -- the behavior the old single search box had. A row with no real name still
    // matches on its username, though it sorts last (nameless rows sort by the null real name).
    UserSearchField(
        USF.name, textsOf = { listOfNotNull(it.name, it.publicName()) }, substring = true, sortOf = { it.name?.lowercase() },
    ),
    // Exact, not substring: the client is a picked id, not a fragment someone types.
    UserSearchField(USF.client, textsOf = { listOf(it.client) }, substring = false, sortOf = { it.client.lowercase() }),
    // Sortable (the default) but filtered by a date range rather than a text term, so it declares no textsOf.
    UserSearchField(USF.updatedAt, textsOf = null, substring = false, sortOf = { it.updatedAt }),
)

/** [userSearchFields] by name, for resolving a text term's field and the requested sort key. */
val userSearchFieldsByName: Map<String, UserSearchField> = userSearchFields.associateBy { it.name }

/**
 * What a caller asked the user search for: the text terms they filtered on (by field name), the update-time
 * range, and how to sort and cap. Built from the request in `AdminEndpoints`; a value object so the pure
 * [searchUserRows] can be exercised without a request.
 */
class UserSearchCriteria(
    /**
     * field name -> search term, for the text-searchable fields the caller filtered on. A field absent here is
     * not constrained; a blank term is dropped before it gets here, since "" would match everything.
     */
    val textTerms: Map<String, String> = emptyMap(),
    /** Low end of the update-time range (inclusive), or null for no lower bound. */
    val updatedAfter: Instant? = null,
    /** High end of the update-time range (inclusive), or null for no upper bound. */
    val updatedBefore: Instant? = null,
    /** The field to sort by; must be a [userSearchFieldsByName] key (the endpoint validates it). */
    val sortBy: String = USF.updatedAt,
    /** Descending (the default): newest, or Z-A, first. */
    val descending: Boolean = true,
    /** How many rows to return; the total matched is reported separately (see [UserSearchPage]). */
    val limit: Int = USF.defaultLimit,
)

/**
 * One page of a user search: the [rows] returned (already capped and sorted) and [numAvailable], how many
 * matched *before* the cap. The two differing is how a caller learns to narrow -- 500 returned of 4000
 * available says the term was too broad, not that there are 500 users.
 */
class UserSearchPage(val rows: List<AuthUserRow>, val numAvailable: Int)

/**
 * Runs [criteria] over [rows] (the caller has already confined them to what the scope admits): apply each
 * text term, then the date range, sort by the chosen field, and cap -- reporting the matched total before the
 * cap. Pure, and covered under Kotest.
 *
 * The sort is made **total** by a `userId` tiebreak, so two rows equal on the sort field (two users with no
 * update time, say) come back in a stable order rather than one the scan happened to produce -- the same
 * reason the cached gedra listing breaks ties by id.
 */
fun searchUserRows(rows: List<AuthUserRow>, criteria: UserSearchCriteria): UserSearchPage {
    var matched = rows.asSequence()

    for ((fieldName, term) in criteria.textTerms) {
        val field = userSearchFieldsByName[fieldName]?.takeIf { it.textsOf != null } ?: continue
        val lower = term.trim().lowercase()
        if (lower.isEmpty()) continue
        val textsOf = field.textsOf!!
        val substring = field.substring
        matched = matched.filter { row ->
            // A field's texts are OR'd: the term matching any one of them (its name or its username, say)
            // matches the field.
            textsOf(row).any { text ->
                val value = text.lowercase()
                if (substring) value.contains(lower) else value == lower
            }
        }
    }
    criteria.updatedAfter?.let { after ->
        matched = matched.filter { (it.updatedAt ?: return@filter false) >= after }
    }
    criteria.updatedBefore?.let { before ->
        matched = matched.filter { (it.updatedAt ?: return@filter false) <= before }
    }

    val field = userSearchFieldsByName[criteria.sortBy] ?: userSearchFieldsByName.getValue(USF.updatedAt)
    val sorted = matched.sortedWith(comparatorFor(field, criteria.descending)).toList()
    return UserSearchPage(sorted.take(criteria.limit), sorted.size)
}

/**
 * The ordering for one field and direction: nulls last in **both** directions, the value compared with the
 * direction applied, and `userId` breaking a tie so the order is total. Nulls are held last rather than
 * flipped with the direction because a row missing the sort value belongs at the end of the list either way,
 * not floated to the top of a descending one.
 */
private fun comparatorFor(field: UserSearchField, descending: Boolean): Comparator<AuthUserRow> {
    val dir = if (descending) -1 else 1
    return Comparator { a, b ->
        @Suppress("UNCHECKED_CAST")
        val av = field.sortOf(a) as Comparable<Any>?
        @Suppress("UNCHECKED_CAST")
        val bv = field.sortOf(b) as Comparable<Any>?
        val primary = when {
            av == null && bv == null -> 0
            av == null -> 1  // nulls last
            bv == null -> -1
            else -> dir * av.compareTo(bv)
        }
        if (primary != 0) primary else a.userId.compareTo(b.userId)
    }
}
