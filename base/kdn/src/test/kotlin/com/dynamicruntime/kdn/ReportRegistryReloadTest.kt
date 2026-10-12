package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientConfigIssues
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GCEL
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.report.RGEN
import com.dynamicruntime.common.gedra.report.RRUN
import com.dynamicruntime.common.gedra.report.ReportKind
import com.dynamicruntime.common.gedra.report.ReportService
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
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

    // A report's typed endpoints (issue #1088) are generated from the registry, so they follow it through a reload:
    // there when the report is, of its type as it is now, and gone when it goes.
    "a stored report's typed endpoints come with it, follow its redefinition, and go with it" {
        val client = "rpttyped"
        val runPath = UADEP.typedReportPath(client, "visits", UADEP.typedReportRun)
        val aggregatePath = UADEP.typedReportPath(client, "visits", UADEP.typedReportAggregate)
        fun endpoints() = SchemaService.get(cxt).schemaStore.endpoints
        fun reload(build: GedraConfigBuilder.() -> Unit) {
            GedraConfigService.get(cxt).writeConfig(writer(cxt, client), config(cxt, client, "site", build), trial = true)
            GedraConfigReload.reloadClient(cxt, client)
        }
        fun siteType() = SchemaService.get(cxt).storeFor(client).types.getValue(RGEN.rowType(client, "visits"))
            .properties.getValue(RRUN.values).valueType!!.properties.getValue("site").valueType!!.jsonType

        // No report, no endpoints; with it, both, and its types in the client's store.
        reload {}
        endpoints().containsKey("$runPath:GET") shouldBe false
        reload { report("visits", "Visits") { column("site", "Site", "form.visit.site") } }
        endpoints().keys.containsAll(listOf("$runPath:GET", "$aggregatePath:GET")) shouldBe true
        siteType() shouldBe "string"

        // Run over a form, which leaves the row's type compiled and cached under the endpoint's path.
        val admin = TestUser.create(cxt, "typed-reload@rpttyped.test", level = ROLE.admin, userClient = client)
        admin.postItem(
            clientPath(GEP.formDocCreate, client),
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to "visit", GE.data to mapOf("site" to "12")))),
        )[GDF.gedraId].shouldNotBeNull()
        fun site() = admin.client.sendJsonGetRequest(runPath)[EP.items].toJsonListOfMaps().single()[RRUN.values].toJsonMapOrEmpty()["site"]
        site() shouldBe "12"

        // The column is redeclared a number: same path, another type. The run answers in it -- which it could not
        // if the type compiled for the old definition were still the one a response is held to.
        reload { report("visits", "Visits") { column("site", "Site", "form.visit.site", kind = ReportKind.number) } }
        siteType() shouldBe "number"
        (site() as Number).toInt() shouldBe 12

        // Redeclared a date, over a text field -- so nothing says whether a value is a day or a moment, and the
        // column is one of moments. A day somebody typed is a day on the shared run, as stored; the typed run, whose
        // type promises a moment, gives the moment that day begins.
        reload { report("visits", "Visits") { column("site", "Site", "form.visit.site", kind = ReportKind.date) } }
        admin.postItem(
            clientPath(GEP.formDocCreate, client),
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to "visit", GE.data to mapOf("site" to "2026-03-01")))),
        )
        fun sites(path: String, args: Map<String, Any?> = emptyMap()) =
            admin.client.sendJsonGetRequest(path, args)[EP.items].toJsonListOfMaps().mapNotNull { it[RRUN.values].toJsonMapOrEmpty()["site"] }
        sites(UADEP.reportRun, mapOf(RRUN.reportId to "visits")) shouldContainExactly listOf("2026-03-01")
        sites(runPath).single().toString() shouldContain "T"

        // A second client's endpoints are untouched by this one's reloads.
        val other = "rpttyped2"
        GedraConfigService.get(cxt).writeConfig(
            writer(cxt, other), config(cxt, other, "site") { report("visits", "Visits") { column("site", "Site", "form.visit.site") } }, trial = true,
        )
        GedraConfigReload.reloadClient(cxt, other)
        val otherRun = "${UADEP.typedReportPath(other, "visits", UADEP.typedReportRun)}:GET"
        endpoints().containsKey(otherRun) shouldBe true

        // The report is dropped: its endpoints and its types go, the other client's stay.
        reload {}
        endpoints().containsKey("$runPath:GET") shouldBe false
        endpoints().containsKey("$aggregatePath:GET") shouldBe false
        SchemaService.get(cxt).schemaStore.types.containsKey(RGEN.rowType(client, "visits")) shouldBe false
        admin.expectError(EXC.notFound, runPath)
        endpoints().containsKey(otherRun) shouldBe true
        SchemaService.get(cxt).schemaStore.types.containsKey(RGEN.rowType(other, "visits")) shouldBe true
    }
})
