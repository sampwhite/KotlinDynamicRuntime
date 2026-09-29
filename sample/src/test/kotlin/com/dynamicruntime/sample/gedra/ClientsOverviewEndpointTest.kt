package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/**
 * The administrators' clients overview and scoped definition retrieve (issue #904): an `allClients` administrator
 * sees every client with its status, origin and counts; a client-scoped administrator sees their own client alone
 * and may not name another; a `public` self-administrator and an ordinary user are refused. The sample's acme and
 * globex are both defined in source, so their origin is `source` with no stored configuration.
 */
class ClientsOverviewEndpointTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "clientsOverview", "clientsOverviewTest", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )
    val admin = TestUser.createFullAdmin(cxt, "overview-admin@example.com")

    fun rows(user: TestUser): Map<String, Map<String, Any?>> =
        user.getItems(UADEP.clientsOverview).associateBy { it[CLD.clientId] as String }

    fun count(row: Map<String, Any?>, key: String): Int = (row[key] as Number).toInt()

    "an allClients administrator sees every present client: status, origin, and what it defines" {
        val listed = rows(admin)
        for (client in listOf(SC.acme, SC.globex)) {
            val row = listed.getValue(client)
            row[CLD.status] shouldBe ClientStatus.present.name
            row[CLD.origin] shouldBe GedraConfigOrigin.source.name
            count(row, CLD.storedConfigs) shouldBe 0
            count(row, CLD.workflowCount) shouldBeGreaterThan 0
            row[CLD.issues].toJsonListOrEmpty().shouldBeEmpty()
        }
        listed.getValue(SC.acme)[CLD.hasSurvey] shouldBe true
    }

    "the counts follow the client's forms and active users, unclaimed ones told apart" {
        val before = rows(admin)
        val acmeBefore = before.getValue(SC.acme)
        val globexBefore = before.getValue(SC.globex)
        // Two forms and one registered user on acme, plus one user invited and not yet claimed.
        val owner = TestUser.create(cxt, "overview-owner@acme.test", userClient = SC.acme)
        val create = clientPath(GEP.formDocCreate, SC.acme)
        repeat(2) {
            owner.postItem(create, mapOf(GDF.entries to listOf(mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to 2026L)))))
        }
        admin.postData(UADEP.userCreate, mapOf(ADF.primaryId to "overview-invited@acme.test", ADF.client to SC.acme))

        val after = rows(admin)
        val acme = after.getValue(SC.acme)
        count(acme, CLD.forms) shouldBe count(acmeBefore, CLD.forms) + 2
        count(acme, CLD.users) shouldBe count(acmeBefore, CLD.users) + 2
        count(acme, CLD.unclaimedUsers) shouldBe count(acmeBefore, CLD.unclaimedUsers) + 1
        // Another client is untouched by acme's activity.
        val globex = after.getValue(SC.globex)
        count(globex, CLD.forms) shouldBe count(globexBefore, CLD.forms)
        count(globex, CLD.users) shouldBe count(globexBefore, CLD.users)
    }

    "a client-scoped administrator sees their own client alone, and its definition, and may not name another" {
        val scoped = TestUser.create(cxt, "overview-scoped@acme.test", level = ROLE.admin, userClient = SC.acme)
        val listed = rows(scoped)
        listed.keys shouldBe setOf(SC.acme)
        listed.getValue(SC.acme)[CLD.status] shouldBe ClientStatus.present.name
        // The retrieve defaults to their own client...
        scoped.getItem(UADEP.clientDefinition)[CLD.client].toJsonMapOrEmpty()[CLD.clientId] shouldBe SC.acme
        // ...and refuses another, where the allClients administrator is answered.
        scoped.expectError(EXC.notAuthorized, UADEP.clientDefinition, args = mapOf(CLD.client to SC.globex))
        admin.getItem(UADEP.clientDefinition, mapOf(CLD.client to SC.globex))[CLD.client].toJsonMapOrEmpty()[CLD.clientId] shouldBe SC.globex
    }

    "a public self-administrator and an ordinary user are refused" {
        val selfAdmin = TestUser.create(cxt, "overview-self@example.com", level = ROLE.admin, userClient = CL.public)
        selfAdmin.expectError(EXC.notAuthorized, UADEP.clientsOverview)
        val user = TestUser.create(cxt, "overview-user@acme.test", userClient = SC.acme)
        user.expectError(EXC.notAuthorized, UADEP.clientsOverview)
    }
})
