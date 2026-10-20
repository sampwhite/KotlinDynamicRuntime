package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.simulation.ListingDemo
import com.dynamicruntime.common.simulation.listingDemoOwnerIndex
import com.dynamicruntime.common.simulation.listingDemoUser
import com.dynamicruntime.common.simulation.provisionListingDemo
import com.dynamicruntime.common.user.UserService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe

/** The listing demo (issue #1316): the counts it is asked for, topped up on a rerun, spread unevenly over owners. */
class ListingDemoTest : StringSpec({
    val cxt = TestInstances.default("listingDemo1316")

    fun formsOf(client: String, scope: ReadScope = ReadScope.ofClient(client)): Int {
        val bound = cxt.mkSubContext("check", client)
        return GedraDataService.get(bound).listGedras(bound, GedraDataType.formDoc, scope, limit = 0).numAvailable
    }

    "a run holds the counts asked for, and a rerun tops up rather than adding again" {
        val client = "${ListingDemo.client}t"
        provisionListingDemo(cxt, "t", formCount = 30, userCount = 12).clients shouldBe listOf(client)
        formsOf(client) shouldBe 30
        // The twelve, besides the administrator; one in six is left for the person to claim.
        val users = UserService.get(cxt).countActiveUsers(cxt, client)
        users.total shouldBe 13
        users.unclaimed shouldBe (0 until 12).count { !listingDemoUser(it, client).registered }

        provisionListingDemo(cxt, "t", formCount = 30, userCount = 12)
        formsOf(client) shouldBe 30
        UserService.get(cxt).countActiveUsers(cxt, client).total shouldBe 13

        provisionListingDemo(cxt, "t", formCount = 40, userCount = 15)
        formsOf(client) shouldBe 40
        UserService.get(cxt).countActiveUsers(cxt, client).total shouldBe 16
    }

    "the forms are spread unevenly: a few owners hold many, and some hold none" {
        val forms = ListingDemo.defaultForms
        val users = ListingDemo.defaultUsers
        val byOwner = (0 until forms).groupingBy { listingDemoOwnerIndex(it, users) }.eachCount()
        // Four in ten go to the heavy owners, so each of them holds a long listing of their own.
        (0 until ListingDemo.heavyOwners).sumOf { byOwner[it] ?: 0 } shouldBe forms * 4 / 10
        (0 until ListingDemo.heavyOwners).forEach { (byOwner[it] ?: 0) shouldBeGreaterThan 40 }
        // About two in five of the users own nothing.
        (users - byOwner.size) shouldBeInRange (users * 3 / 10)..(users / 2)
    }
})
