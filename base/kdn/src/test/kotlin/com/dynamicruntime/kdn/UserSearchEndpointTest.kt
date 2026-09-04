package com.dynamicruntime.kdn

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.USF
import com.dynamicruntime.common.util.toJsonListOfMaps
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * The brute-force user-cache search endpoint, end to end (issue #411): a full-scope administrator over the
 * in-process pipeline, so the section gate, the input coercion, the list envelope and the response-schema
 * validation (which `mkTestBootCxt` turns on) are all exercised for real.
 *
 * The in-memory database is **shared across every spec in the JVM run** (the #408 pollution lesson), so this
 * one cannot assert on the whole population. Every assertion is instead scoped by a globally-unique marker --
 * the `usrch.test` email domain and the `usrch_` username prefix, which no other spec uses -- so another
 * spec's users can never drift into a count here.
 */
class UserSearchEndpointTest : StringSpec({

    val cxt = Startup.mkTestBootCxt("userSearch", "userSearchEndpointTest")
    val admin = TestUser.createFullAdmin(cxt, "chief@admin.test")

    // Three users under the unique `usrch.test` domain (so a search isolates them from the admin, from the
    // odd-one-out, and from every other spec's users in the shared db), plus one that must never match it.
    fun create(email: String, username: String) {
        admin.postData(UADEP.userCreate, mapOf(ADF.primaryId to email, ADF.username to username))
    }
    create("alice@usrch.test", "usrch_alice")
    create("bob@usrch.test", "usrch_bob")
    create("carol@usrch.test", "usrch_carol")
    create("dave@uelse.test", "usrch_dave_x")

    /** The raw list envelope, for the paging fields `getItems` does not surface. */
    fun search(args: Map<String, Any?>): Map<String, Any?> = admin.client.sendJsonGetRequest(UADEP.userSearch, args)

    fun emails(env: Map<String, Any?>): List<String> =
        env[EP.items].toJsonListOfMaps().map { it[ADF.primaryId] as String }

    "an email substring finds the matching users and not the others" {
        val env = search(mapOf(USF.email to "usrch.test"))
        emails(env).sorted() shouldContainExactly listOf("alice@usrch.test", "bob@usrch.test", "carol@usrch.test")
        env[EP.numItems] shouldBe 3
    }

    "a public-name substring matches the username" {
        emails(search(mapOf(USF.publicName to "usrch_alice"))) shouldContainExactly listOf("alice@usrch.test")
    }

    // The single "any text" q term (issue #581): one box matching email, username, or real name at once, OR
    // across the fields. Its own `qsrch.test` marker, so its rows never drift into the counts above.
    "the q term matches across email, username, and name at once" {
        admin.postData(UADEP.userCreate, mapOf(ADF.primaryId to "egg@qsrch.test", ADF.username to "qsrch_egg", ADF.name to "Zelda Fitzwilliam"))
        admin.postData(UADEP.userCreate, mapOf(ADF.primaryId to "bee@qsrch.test", ADF.username to "qsrch_bee"))
        // A real name carried by no email or username.
        emails(search(mapOf(EI.q to "fitzwilliam"))) shouldContainExactly listOf("egg@qsrch.test")
        // A username fragment.
        emails(search(mapOf(EI.q to "qsrch_bee"))) shouldContainExactly listOf("bee@qsrch.test")
        // The shared domain fragment returns both -- OR across fields, where two named terms would AND.
        emails(search(mapOf(EI.q to "qsrch.test"))).sorted() shouldContainExactly listOf("bee@qsrch.test", "egg@qsrch.test")
    }

    "every returned row carries an update time -- the projection surfaces it" {
        val rows = search(mapOf(USF.email to "usrch.test"))[EP.items].toJsonListOfMaps()
        rows.all { it[ADF.updatedAt] != null } shouldBe true
    }

    "sorting by email ascending orders on the address" {
        val env = search(mapOf(USF.email to "usrch.test", USF.sortBy to USF.email, USF.descending to false))
        emails(env) shouldContainExactly listOf("alice@usrch.test", "bob@usrch.test", "carol@usrch.test")
    }

    "the limit caps the page while numAvailable and hasMore report the full match" {
        val env = search(mapOf(USF.email to "usrch.test", EP.limit to 2))
        env[EP.numItems] shouldBe 2
        env[EP.numAvailable] shouldBe 3
        env[EP.hasMore] shouldBe true
    }

    "an unknown sort field is a plain input error" {
        admin.expectError(EXC.badInput, UADEP.userSearch, args = mapOf(USF.sortBy to "bogus"))
    }

    "the client filter narrows to an existing client and excludes a bogus one" {
        // The created users share the admin's client (the default). Read it back, filter by it, and by one
        // that cannot exist -- the scope admits every client for a full admin, so the filter is what narrows.
        val someClient = search(mapOf(USF.email to "usrch.test"))[EP.items].toJsonListOfMaps()
            .first()[ADF.client] as String
        emails(search(mapOf(USF.email to "usrch.test", USF.client to someClient))).size shouldBe 3
        emails(search(mapOf(USF.email to "usrch.test", USF.client to "no.such.client"))) shouldHaveSize 0
    }

    "the update-time range filters, and coerces its ISO bounds" {
        // Everyone was just created, so a floor in the distant past admits them all and a ceiling there admits
        // none -- which also proves the ISO string coerces to an instant on the wire.
        emails(search(mapOf(USF.email to "usrch.test", USF.updatedAfter to "2000-01-01T00:00:00Z"))).size shouldBe 3
        emails(search(mapOf(USF.email to "usrch.test", USF.updatedBefore to "2000-01-01T00:00:00Z"))) shouldHaveSize 0
    }

    "the name filter matches the username, not just the real-world name" {
        // usrch_alice has no real name set, so this only matches via the username -- the pre-#411 behavior.
        emails(search(mapOf(USF.name to "usrch_alice"))) shouldContainExactly listOf("alice@usrch.test")
    }

    "a negative limit is a harmless empty page, not a 500" {
        // Without the floor, `List.take(-1)` would throw and surface as a 500; it should be an empty page whose
        // numAvailable still reports the full match.
        val env = search(mapOf(USF.email to "usrch.test", EP.limit to -1))
        env[EP.numItems] shouldBe 0
        env[EP.numAvailable] shouldBe 3
    }

    "a mutation response reports the post-write update time, not the stale one" {
        // A separate `ustamp.test` domain so this extra account never drifts into the `usrch.test` counts above.
        val created = admin.postData(UADEP.userCreate, mapOf(ADF.primaryId to "stamp@ustamp.test", ADF.username to "ustamp_1"))
        val id = created[ADF.userId] as Long
        // Set the name -- a real write -- and confirm the returned row's updatedAt matches what a fresh search
        // reports, rather than the (older) value the row was read at before the write.
        val afterWrite = admin.postData(UADEP.userSetName, mapOf(ADF.userId to id, ADF.name to "Stamped"))[ADF.updatedAt]
        val fromSearch = search(mapOf(USF.email to "stamp@ustamp.test"))[EP.items].toJsonListOfMaps().single()[ADF.updatedAt]
        (afterWrite != null) shouldBe true
        afterWrite shouldBe fromSearch
    }

    "a client-scoped administrator sees their client's users through the client index" {
        // A scoped admin (admin level, no allClients) is confined to their own client, so searchUsers serves
        // from the cache's client index rather than the whole table. They see the same-client users the full
        // admin created (all default to the same client), isolated here by the unique marker. Its own email is
        // on a separate domain so this admin account never drifts into the `usrch.test` counts above.
        val scoped = TestUser.create(cxt, "scopedadmin@uscope.test", level = ROLE.admin)
        val env = scoped.client.sendJsonGetRequest(UADEP.userSearch, mapOf(USF.email to "usrch.test"))
        emails(env).sorted() shouldContainExactly listOf("alice@usrch.test", "bob@usrch.test", "carol@usrch.test")
    }
})
