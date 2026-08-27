package com.dynamicruntime.common.user

import kotlin.time.Instant

/**
 * A closed-at-both-ends date range, either end optional (issue #462).
 *
 * A pair rather than two loose values because that is what a caller supplies and what a filter consumes: the
 * two halves of one question, and keeping them together is what lets a date field be one map entry rather
 * than two.
 */
class InstantRange(val after: Instant? = null, val before: Instant? = null) {
    /** Whether this constrains anything -- a range with neither end is not a filter. */
    val isEmpty: Boolean get() = after == null && before == null
}

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
 * (when it declares [UserSearchField.textsOf]) and sortable, with no new code path. [searchUserRows] below is
 * pure -- rows in, a page out -- so it is covered under Kotest without a server.
 */

/**
 * One searchable and/or sortable user attribute.
 *
 * Every field is **sortable** ([sortOf]); a field is additionally **text-searchable** when it declares
 * [textsOf]. The update time is the one field that is sortable but not text-searchable -- it is filtered by a
 * date *range* instead (see [UserSearchCriteria.dateRanges]), which is a different shape of filter and lives
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
     * The value the field sorts by. Null sorts **last** regardless of direction -- an account with no
     * real-world name has nothing to order by, so it belongs at the end either way rather than heading an
     * ascending list of names.
     *
     * A **date** field never returns null, and so never meets that rule: it returns a floor instead, which
     * puts absence below every date (issue #462, and see `asSearchField`). The difference is real rather than
     * an inconsistency -- a missing name is no information, while a missing date genuinely is earlier than
     * any date, and an administrator sorting by oldest is hunting exactly those rows.
     */
    val sortOf: (AuthUserRow) -> Comparable<*>?,
)

/**
 * One **date** attribute of a user: the wire names it carries (issue #462) and how to read it off a row.
 *
 * Split across two modules on purpose. [UserDateKeys] generates the three names in the kernel, so the console
 * derives the same strings the endpoint reads; [instantOf] cannot live there, because it needs [AuthUserRow].
 * Pairing them here is what lets one entry produce a sort key, a filterable range and a column at once.
 */
class UserDateField(val keys: UserDateKeys, val instantOf: (AuthUserRow) -> Instant?)

/**
 * The date attributes a caller may sort on and filter by range (issue #462).
 *
 * Adding one here gives it a sort key, an `After`/`Before` pair on the endpoint, and a filter in
 * [searchUserRows] -- none of which is written per date. That matters at five dates rather than one: the
 * previous shape had the range hard-coded in the criteria, in the filter and in the endpoint's declaration,
 * so four more dates meant two dozen near-identical lines that all had to agree.
 */
val userDateFields: List<UserDateField> = listOf(
    UserDateField(USF.updated) { it.updatedAt },
    UserDateField(USF.registered) { it.registeredAt },
    UserDateField(USF.activated) { it.activatedAt },
    UserDateField(USF.lastLoggedIn) { it.lastLoggedInAt },
    UserDateField(USF.lastEdited) { it.lastEditedAt },
)

/** [userDateFields] by root, for resolving the range keys a caller filtered on. */
val userDateFieldsByRoot: Map<String, UserDateField> = userDateFields.associateBy { it.keys.root }

/**
 * The sortable entry a date attribute contributes, so a date is declared once rather than in two registries.
 *
 * **Absence sorts below every date**, which is the point rather than a detail: an administrator sorting by
 * oldest login is hunting dormant accounts, and never having logged in is the most dormant state there is --
 * burying those rows at the bottom in both directions would hide exactly what the sort was opened to find.
 * Returning a floor rather than null is also what keeps [UserSearchField]'s own nulls-last rule intact for
 * the fields that want it: this field simply never returns one.
 */
private fun UserDateField.asSearchField(): UserSearchField =
    UserSearchField(keys.at, textsOf = null, substring = false, sortOf = { instantOf(it) ?: Instant.DISTANT_PAST })

/**
 * The searchable/sortable user attributes (issue #411). Add an attribute here and it is searchable (if it
 * names [UserSearchField.textsOf]) and sortable at once; the console offers the same set as columns to order
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
) + userDateFields.map { it.asSearchField() }


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
    /**
     * date root -> the range the caller asked for, for the date fields they filtered on (issue #462). Keyed
     * by [UserDateKeys.root] and shaped like [textTerms] deliberately: a date filter and a text filter are
     * the same kind of thing to everything downstream, and were only written differently because there used
     * to be one date.
     */
    val dateRanges: Map<String, InstantRange> = emptyMap(),
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
    for ((root, range) in criteria.dateRanges) {
        val field = userDateFieldsByRoot[root] ?: continue
        val instantOf = field.instantOf
        // A row with no date matches **no** range, in either direction. It is not "before everything": a
        // user who never logged in did not log in before Tuesday, and a range filter is asking which rows
        // fall inside a window rather than sorting them.
        range.after?.let { after -> matched = matched.filter { (instantOf(it) ?: return@filter false) >= after } }
        range.before?.let { before -> matched = matched.filter { (instantOf(it) ?: return@filter false) <= before } }
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
