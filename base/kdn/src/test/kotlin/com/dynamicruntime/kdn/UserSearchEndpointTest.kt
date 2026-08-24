package com.dynamicruntime.kdn

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
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
})
