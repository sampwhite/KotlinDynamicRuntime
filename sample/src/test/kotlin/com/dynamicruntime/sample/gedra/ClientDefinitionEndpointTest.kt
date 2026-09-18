package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.UF
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.startup.ComponentDefinition
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
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

/**
 * The admin client-definition retrieve and cross-client summary endpoints (issue #672): an `allClients` admin
 * reads one client's definition (its attributes, the traits it supports as metadata, its usage rules and its
 * workflow ids), or a brief summary across every client. Both are full-scope, so a scoped administrator is
 * refused.
 *
 * The sample is loaded, so `acme` is a real client with its own trait (`acmeSiteAudit`), two usage columns
 * (Auditor, Year), a creation workflow, and -- crucially for the supported-vs-visible check -- a *global* trait
 * it deliberately omits (`managerApproval`).
 */
class ClientDefinitionEndpointTest : StringSpec({
    // The sample plus a fixture client enabled only in `local` (issue #703): a `unit` boot therefore *knows* it
    // but does not *carry* it, which is what lets the present-only contract be tested -- a `known()`-based handler
    // would serve it and these tests would catch that.
    val cxt = Startup.mkTestBootCxt(
        "clientDef", "clientDefTest",
        mapOf("KDR_LOAD_SAMPLE" to "true", RetiredClientComponent.loadFlag.name to "true"),
        additionalComponents = listOf(SampleComponent(), RetiredClientComponent()),
    )

    "an allClients admin retrieves a client's definition -- attributes, supported traits, usages and workflows" {
        val admin = TestUser.createFullAdmin(cxt, "clientdef-admin@example.com")
        val def = admin.getItem(ADEP.clientDefinition, mapOf(CLD.client to SC.acme))

        // The attributes are acme's, as ClientDef.toInfo() writes them.
        def[CLD.client].toJsonMapOrEmpty()[CLD.clientId].toOptStr() shouldBe SC.acme

        // The traits include acme's own site-audit trait, with its metadata (generated type + applicable kinds).
        val traits = def[CLD.traits].toJsonListOfMaps()
        val siteAudit = traits.first { it[CCT.traitId].toOptStr() == SC.siteAudit }
        siteAudit[CCT.typeName].toOptStr()!! shouldStartWith SC.acmeNamespace
        siteAudit[CCT.appliesTo].toJsonListOfStrings() shouldContain GedraDataType.formDoc.name

        // Supported, not merely visible (issue #672 review): acme omits the global `managerApproval` trait, so it
        // must not appear even though acme can see it.
        traits.map { it[CCT.traitId].toOptStr() } shouldNotContain ST.managerApproval

        // The usage rules are the columns acme declares.
        def[CLD.usages].toJsonListOfMaps().map { it[UF.label].toOptStr() } shouldContain "Auditor"

        // Acme has a creation workflow, so its workflow list is not empty.
        def[CLD.workflows].toJsonListOfStrings().shouldNotBeEmpty()
    }

    "retrieving a client that is not present is a 404, not a null item" {
        val admin = TestUser.createFullAdmin(cxt, "clientdef-absent@example.com")
        admin.expectError(EXC.notFound, ADEP.clientDefinition, args = mapOf(CLD.client to "nosuchclient"))
    }

    "the summary listing reports every client with its workflow ids, supported trait ids and usage labels" {
        val admin = TestUser.createFullAdmin(cxt, "clientdef-summary@example.com")
        val rows = admin.getItems(ADEP.clientSummaries)
        val acme = rows.first { it[CLD.clientId].toOptStr() == SC.acme }
        acme[CLD.usageLabels].toJsonListOfStrings() shouldContain "Auditor"
        // The survey fact (issue #695): acme declares a survey, globex does not -- what a forms surface working
        // in the chosen client keys its survey-status filter on.
        acme[CLD.hasSurvey] shouldBe true
        rows.first { it[CLD.clientId] == SC.globex }[CLD.hasSurvey] shouldBe false
        acme[CLD.traitIds].toJsonListOfStrings() shouldContain SC.siteAudit
        acme[CLD.traitIds].toJsonListOfStrings() shouldNotContain ST.managerApproval
        acme[CLD.workflowIds].toJsonListOfStrings().shouldNotBeEmpty()
    }

    "a scoped administrator without allClients is refused both endpoints" {
        val scoped = TestUser.create(cxt, "clientdef-scoped@example.com", level = ROLE.admin)
        // The premise the refusal turns on: a plain admin does not carry the capability.
        scoped.selfRoles() shouldNotContain ROLE.allClients
        scoped.expectError(EXC.notAuthorized, ADEP.clientDefinition, args = mapOf(CLD.client to SC.acme))
        scoped.expectError(EXC.notAuthorized, ADEP.clientSummaries)
    }

    "a client known but not present in this environment is a 404, and absent from the summary (issue #703)" {
        // The retired fixture is enabled only in `local`, so this `unit` boot knows it and does not carry it.
        // Present-only, not merely known: a retrieve is a 404 (the same as an unknown client), and the
        // cross-client summary lists the present clients, so it leaves the retired one out.
        ClientService.get(cxt).known(RetiredClientComponent.clientId).shouldNotBeNull()
        ClientService.get(cxt).present(RetiredClientComponent.clientId) shouldBe null

        val admin = TestUser.createFullAdmin(cxt, "clientdef-retired@example.com")
        admin.expectError(EXC.notFound, ADEP.clientDefinition, args = mapOf(CLD.client to RetiredClientComponent.clientId))
        admin.getItems(ADEP.clientSummaries).map { it[CLD.clientId].toOptStr() } shouldNotContain RetiredClientComponent.clientId
    }

    "testFeatures is projected on a test instance and neutralized off one (issue #703)" {
        // acme is the one sample client declaring `testFeatures`. On a test instance the retrieve's projection
        // (`ClientDef.toInfo()`) carries it; on a non-test node `ClientService` strips it from the present
        // definition, so the very same projection omits the key -- the boundary #696 draws, seen through toInfo().
        ClientService.get(cxt).present(SC.acme).shouldNotBeNull().toInfo().containsKey(CLD.testFeatures) shouldBe true

        val prodCxt = Startup.mkTestBootCxt(
            "clientDefProd", "clientDefProdTest",
            mapOf(ACFG.isTestInstance to false, "KDR_LOAD_SAMPLE" to "true"),
            additionalComponents = listOf(SampleComponent()),
        )
        ClientService.get(prodCxt).present(SC.acme).shouldNotBeNull().toInfo().containsKey(CLD.testFeatures) shouldBe false
    }

    "an unauthenticated caller reaches neither endpoint (401)" {
        val anon = TestHttpClient(cxt.instanceConfig)
        (anon.sendJsonGetRequest(ADEP.clientDefinition, mapOf(CLD.client to SC.acme))[EP.status] as? Number)?.toInt() shouldBe 401
        (anon.sendJsonGetRequest(ADEP.clientSummaries)[EP.status] as? Number)?.toInt() shouldBe 401
    }
})

/**
 * A fixture component contributing one client enabled in `local` only (issue #703), so a `unit` boot knows it
 * and does not carry it -- the population the present-only tests need. Self-gates on [loadFlag] the way
 * `SampleComponent` gates on `KDR_LOAD_SAMPLE`, since components register once per VM and a fixture that loaded
 * unconditionally would drop its client into every other test's boot. Mirrors `base/kdn`'s `OffsiteClientComponent`.
 */
class RetiredClientComponent : ComponentDefinition {
    override val providerName: String = "retiredClientFixture"

    override fun isLoaded(cxt: KdrCxt): Boolean = cxt.getEnvBool(loadFlag) == true

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "retiredClient", "retiredconfig", clientId) {
            defineClient(
                ClientDef(
                    clientId = clientId,
                    name = "Retired",
                    description = "A client this node knows about and does not carry.",
                    usageType = ClientUsageType.dev,
                    audience = ClientAudience.customer,
                    enabledEnvironments = setOf(ENV.local),
                ),
            )
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        val loadFlag = EnvVarDef(
            "KDR_LOAD_RETIRED_CLIENT", group = ENVGRP.application, defaultDoc = "off",
            description = "Test-only flag that loads the retired-client fixture regardless of environment.",
        )
        const val clientId = "retired703"
    }
}
