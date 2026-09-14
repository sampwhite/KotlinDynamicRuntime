package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

/**
 * The admin client-definition retrieve and cross-client summary endpoints (issue #672): an `allClients` admin
 * reads one client's full definition (its attributes, traits with data schema, usage rules and workflow ids),
 * or a brief summary across every client. Both are full-scope, so a scoped administrator is refused.
 *
 * The sample is loaded, so `acme` is a real client with its own trait (`acmeSiteAudit`), two usage columns
 * (Auditor, Year) and a creation workflow to assert against.
 */
class ClientDefinitionEndpointTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "clientDef", "clientDefTest", mapOf("KDR_LOAD_SAMPLE" to "true"), additionalComponents = listOf(SampleComponent()),
    )

    "an allClients admin retrieves a client's full definition -- attributes, traits, usages and workflows" {
        val admin = TestUser.createFullAdmin(cxt, "clientdef-admin@example.com")
        val def = admin.getItem(ADEP.clientDefinition, mapOf(CLD.client to SC.acme))

        // The attributes are acme's, as ClientDef.toInfo() writes them.
        def[CLD.client].toJsonMapOrEmpty()[CLD.clientId].toOptStr() shouldBe SC.acme

        // The traits include acme's own site-audit trait, carrying its data schema (an object).
        val traits = def[CLD.traits].toJsonListOfMaps()
        val siteAudit = traits.first { it[CLD.traitId].toOptStr() == SC.siteAudit }
        siteAudit[CLD.typeName].toOptStr()!! shouldStartWith SC.acmeNamespace
        siteAudit[CLD.dataSchema].toJsonMapOrEmpty().shouldNotBeEmpty()

        // The usage rules are the columns acme declares.
        def[CLD.usages].toJsonListOfMaps().map { it[CLD.label].toOptStr() } shouldContain "Auditor"

        // Acme has a creation workflow, so its workflow list is not empty.
        def[CLD.workflows].toJsonListOfStrings().shouldNotBeEmpty()
    }

    "retrieving a client that is not present returns no item" {
        val admin = TestUser.createFullAdmin(cxt, "clientdef-absent@example.com")
        admin.getItem(ADEP.clientDefinition, mapOf(CLD.client to "nosuchclient")).shouldBeEmpty()
    }

    "the summary listing reports every client with its workflow ids, trait ids and usage labels" {
        val admin = TestUser.createFullAdmin(cxt, "clientdef-summary@example.com")
        val rows = admin.getItems(ADEP.clientSummaries)
        val acme = rows.first { it[CLD.clientId].toOptStr() == SC.acme }
        acme[CLD.usageLabels].toJsonListOfStrings() shouldContain "Auditor"
        acme[CLD.traitIds].toJsonListOfStrings() shouldContain SC.siteAudit
        acme[CLD.workflowIds].toJsonListOfStrings().shouldNotBeEmpty()
    }

    "a scoped administrator without allClients is refused both endpoints" {
        val scoped = TestUser.create(cxt, "clientdef-scoped@example.com", level = ROLE.admin)
        scoped.expectError(EXC.notAuthorized, ADEP.clientDefinition, args = mapOf(CLD.client to SC.acme))
        scoped.expectError(EXC.notAuthorized, ADEP.clientSummaries)
    }
})
