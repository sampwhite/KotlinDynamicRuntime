package com.dynamicruntime.common.user

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

/**
 * Pure coverage for the brute-force user search (issue #411): the filters (substring email/name, exact
 * client, update-time range), the sort (by any field, both directions, nulls and ties handled), and the cap
 * with its matched-total report. Rows in, a page out -- no cache, no server.
 */
class UserSearchTest : StringSpec({

    fun user(
        id: Long, email: String, username: String, client: String = "acme", updatedAt: Instant? = null,
        name: String? = null,
    ): AuthUserRow {
        val row = AuthUserRow(id, client, email)
        row.username = username
        row.updatedAt = updatedAt
        row.name = name
        row.enabled = true
        return row
    }

    val t1 = Instant.parse("2026-01-01T00:00:00Z")
    val t2 = Instant.parse("2026-02-01T00:00:00Z")
    val t3 = Instant.parse("2026-03-01T00:00:00Z")

    val ada = user(1, "ada@example.com", "ada_l", updatedAt = t2)
    val grace = user(2, "grace@example.com", "grace_h", updatedAt = t3)
    val alan = user(3, "alan@other.com", "alan_t", client = "globex", updatedAt = t1)
    val all = listOf(ada, grace, alan)

    fun ids(page: UserSearchPage) = page.rows.map { it.userId }

    "an email substring matches case-insensitively" {
        val page = searchUserRows(all, UserSearchCriteria(textTerms = mapOf(USF.email to "EXAMPLE")))
        ids(page).sorted() shouldBe listOf(1L, 2L)
        page.numAvailable shouldBe 2
    }

    "a public-name substring matches the username" {
        val page = searchUserRows(all, UserSearchCriteria(textTerms = mapOf(USF.publicName to "grace")))
        ids(page) shouldBe listOf(2L)
    }

    "a name substring matches the real-world name" {
        val named = user(10, "e@x.com", "e_dith", updatedAt = t1, name = "Édith Piaf")
        val nameless = user(11, "f@x.com", "f_user", updatedAt = t1)
        val page = searchUserRows(
            listOf(named, nameless), UserSearchCriteria(textTerms = mapOf(USF.name to "piaf")),
        )
        ids(page) shouldBe listOf(10L)
    }

    "the name filter also matches the username -- even for an account with no real name" {
        // The name field ORs the real-world name and the public name (username), so pasting a login handle into
        // the console's Name box finds the account, the pre-#411 behavior the old search box had.
        val named = user(12, "gh@x.com", "grace_hopper", updatedAt = t2, name = "Grace")
        val nameless = user(13, "z@x.com", "zeta_user", updatedAt = t1) // no real name
        // "hopper" is only in the username, not the real name "Grace".
        ids(searchUserRows(listOf(named, nameless), UserSearchCriteria(textTerms = mapOf(USF.name to "hopper")))) shouldBe
            listOf(12L)
        // A nameless account is still found by its handle.
        ids(searchUserRows(listOf(named, nameless), UserSearchCriteria(textTerms = mapOf(USF.name to "zeta")))) shouldBe
            listOf(13L)
    }

    "a placeholder username matches on the email as its public name" {
        // needsRealUsername (a '@'-prefixed placeholder) means publicName() falls back to the email.
        val placeholder = user(4, "zoe@example.com", "@zoe@example.com", updatedAt = t1)
        val page = searchUserRows(
            listOf(placeholder), UserSearchCriteria(textTerms = mapOf(USF.publicName to "zoe@example")),
        )
        ids(page) shouldBe listOf(4L)
    }

    "the client filter is exact, not a substring" {
        searchUserRows(all, UserSearchCriteria(textTerms = mapOf(USF.client to "globex"))).let { ids(it) } shouldBe listOf(3L)
        // "glob" is a substring of "globex" but the client match is exact, so it finds nothing.
        searchUserRows(all, UserSearchCriteria(textTerms = mapOf(USF.client to "glob"))).rows.size shouldBe 0
    }

    "multiple terms compose as AND" {
        val page = searchUserRows(
            all, UserSearchCriteria(textTerms = mapOf(USF.email to "example", USF.publicName to "ada")),
        )
        ids(page) shouldBe listOf(1L)
    }

    "the update-time range is inclusive on both ends" {
        // [t2, t3] admits ada (t2) and grace (t3) but not alan (t1).
        val page = searchUserRows(
            all,
            UserSearchCriteria(dateRanges = mapOf(USF.updated.root to InstantRange(after = t2, before = t3))),
        )
        ids(page).sorted() shouldBe listOf(1L, 2L)
    }

    "the default sort is updatedAt descending -- newest first" {
        ids(searchUserRows(all, UserSearchCriteria())) shouldBe listOf(2L, 1L, 3L)
    }

    "sorting ascending reverses it" {
        ids(searchUserRows(all, UserSearchCriteria(descending = false))) shouldBe listOf(3L, 1L, 2L)
    }

    "sorting by email orders on the address" {
        // ada@, alan@, grace@ ascending.
        ids(searchUserRows(all, UserSearchCriteria(sortBy = USF.email, descending = false))) shouldBe
            listOf(1L, 3L, 2L)
    }

    "a missing date sorts below every date, in both directions" {
        // Changed by #462, and the pair below is the reason. A dateless row is not "no information" the way a
        // nameless one is -- it is genuinely earlier than any date, and an administrator sorting by oldest is
        // hunting exactly those rows. Nulls-last would bury them at the bottom whichever way the sort ran,
        // hiding what the sort was opened to find.
        val dateless = user(9, "zzz@example.com", "zzz", updatedAt = null)
        val rows = all + dateless
        // Descending (newest first): the dateless row is last, as it always was.
        ids(searchUserRows(rows, UserSearchCriteria(descending = true))).last() shouldBe 9L
        // Ascending (oldest first): now **first**, which is the half that changed.
        ids(searchUserRows(rows, UserSearchCriteria(descending = false))).first() shouldBe 9L
    }

    "a missing text value still sorts last in both directions" {
        // The other half of the rule, and why it is two rules rather than one: an account with no real-world
        // name has nothing to order by, so it belongs at the end either way rather than heading an ascending
        // list of names.
        val nameless = user(9, "zzz@example.com", "zzz").also { it.name = null }
        val rows = all + nameless
        ids(searchUserRows(rows, UserSearchCriteria(sortBy = USF.name, descending = true))).last() shouldBe 9L
        ids(searchUserRows(rows, UserSearchCriteria(sortBy = USF.name, descending = false))).last() shouldBe 9L
    }

    "a tie on the sort field breaks by userId for a stable order" {
        // Two users with no update time tie on the (null) sort value; userId decides.
        val a = user(7, "a@x.com", "a", updatedAt = null)
        val b = user(5, "b@x.com", "b", updatedAt = null)
        ids(searchUserRows(listOf(a, b), UserSearchCriteria())) shouldBe listOf(5L, 7L)
    }

    "the limit caps the rows but numAvailable reports the full match" {
        val page = searchUserRows(all, UserSearchCriteria(limit = 2))
        page.rows.size shouldBe 2
        page.numAvailable shouldBe 3
        // The cap keeps the first two of the sorted order (newest first).
        ids(page) shouldBe listOf(2L, 1L)
    }

    "a blank term is no filter rather than a match-nothing" {
        searchUserRows(all, UserSearchCriteria(textTerms = mapOf(USF.email to "   "))).rows.size shouldBe 3
    }

    // --- the shared spec agrees with the backend registry (issue #411, SDUI) ---------------------------
    //
    // The console renders from `userSearchFieldSpecs` (base/kernel) while the actual filtering/sorting runs off
    // `userSearchFields` (here). They are two halves of one description -- what a field is vs how to read it off
    // a row -- so this pins that they cannot drift: a spec field with no accessor, or a filter kind that
    // disagrees with the accessor, would render a control the backend cannot honor.

    "every console spec field has a backing registry entry, and the two agree on how it filters" {
        for (spec in userSearchFieldSpecs) {
            val field = userSearchFieldsByName[spec.name]
                ?: throw AssertionError("Spec field '${spec.name}' has no accessor in userSearchFields.")
            when (spec.filterKind) {
                // A text filter (substring/exact) needs texts to match against; the exact/substring flag must agree.
                UserFilterKind.substring -> {
                    (field.textsOf != null) shouldBe true
                    field.substring shouldBe true
                }
                UserFilterKind.exact -> {
                    (field.textsOf != null) shouldBe true
                    field.substring shouldBe false
                }
                // A date-range field is not text-searchable and must name the two bound params the endpoint reads.
                UserFilterKind.dateRange -> {
                    (field.textsOf == null) shouldBe true
                    (spec.rangeKeys != null) shouldBe true
                }
                // A sort-only field would carry no filter input; none exist today, but the mapping stays total.
                null -> (field.textsOf == null) shouldBe true
            }
        }
    }

    "the shared sort keys are exactly the sortable spec fields" {
        userSortKeys shouldBe userSearchFieldSpecs.filter { it.sortable }.map { it.name }.toSet()
        // And each is a real registry entry the endpoint will accept as a sort field.
        userSortKeys.all { userSearchFieldsByName.containsKey(it) } shouldBe true
    }
})
