package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientConfigIssues
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GCEL
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.report.ReportKind
import com.dynamicruntime.common.gedra.report.ReportService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The report registry on a booted node (issue #980): a stored report bound when its client reloads, a write whose
 * report reads nothing refused by its trial, and -- under the forgiving stored mode -- a report a trait change
 * breaks dropped on the reload that makes the change, with the rest of the configuration loading.
 */
class ReportRegistryReloadTest : StringSpec({
    val cxt = TestInstances.default("reportRegistry")

    fun writer(on: KdrCxt, client: String): KdrCxt = on.mkSubContext("reportWrite", client).also { it.userId = 9800L }

    fun config(on: KdrCxt, client: String, field: String, build: GedraConfigBuilder.() -> Unit) =
        gedraConfig(on, "main", "client.$client", client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            trait("VisitEntry", "visit", setOf(GedraDataType.formDoc), "A site visit.") { property(field, "Where.") }
            build()
        }

    "a report added by a stored write is bound and available after the reload" {
        val client = "rptadd"
        val written = config(cxt, client, "site") {
            report("visits", "Visits") { column("site", "Site", "form.visit.site") }
        }
        GedraConfigService.get(cxt).writeConfig(writer(cxt, client), written, trial = true)
        ReportService.get(cxt).forClient(client).report("visits").shouldBeNull()
        GedraConfigReload.reloadClient(cxt, client)
        val bound = ReportService.get(cxt).forClient(client).report("visits").shouldNotBeNull()
        bound.bound.columns.single().kind shouldBe ReportKind.string
        bound.fromTemplate shouldBe false
    }

    "a write whose report reads a field that is not there is refused by its trial, naming the path" {
        val client = "rptbadpath"
        val written = config(cxt, client, "site") {
            report("visits", "Visits") { column("site", "Site", "form.visit.place") }
        }
        val message = shouldThrow<KdrException> {
            GedraConfigService.get(cxt).writeConfig(writer(cxt, client), written, trial = true)
        }.message.shouldNotBeNull()
        message shouldContain "Report 'visits'"
        message shouldContain "form.visit.place"
        GedraConfigService.get(cxt).listConfigs(writer(cxt, client)).size shouldBe 0
    }

    "a report a trait change breaks is dropped on that reload, and the rest of the configuration loads" {
        val warn = TestInstances.storedConfigWarn("reportRegistryWarn")
        val client = "rptbreak"
        val svc = GedraConfigService.get(warn)
        fun reports(field: String) = config(warn, client, field) {
            report("visits", "Visits") { column("site", "Site", "form.visit.site") }
            report("owners", "Owners") { column("email", "Email", "user.email") }
        }
        svc.writeConfig(writer(warn, client), reports("site"))
        GedraConfigReload.reloadClient(warn, client)
        ReportService.get(warn).forClient(client).reports.keys.toList() shouldContainExactly listOf("visits", "owners")

        // The trait's field is renamed; the report still reads the old one. Written without a trial, as an import is.
        svc.writeConfig(writer(warn, client), reports("place"))
        val result = GedraConfigReload.reloadClient(warn, client)
        ClientService.get(warn).present(client).shouldNotBeNull()
        ReportService.get(warn).forClient(client).reports.keys.toList() shouldContainExactly listOf("owners")
        val issue = result.issues.single { it.elementKind == GCEL.report }
        issue.elementId shouldBe "visits"
        issue.message shouldContain "form.visit.site"
        ClientConfigIssues.get(warn).issuesFor(client).any { it.elementId == "visits" } shouldBe true
        ReportService.get(warn).issues.any { it.client == client && it.elementId == "visits" } shouldBe true

        // Put right, the report is back, and the issue gone.
        svc.writeConfig(writer(warn, client), reports("site"))
        GedraConfigReload.reloadClient(warn, client).issues.none { it.elementKind == GCEL.report } shouldBe true
        ReportService.get(warn).forClient(client).report("visits").shouldNotBeNull()
        ReportService.get(warn).issues.none { it.client == client } shouldBe true
    }
})
