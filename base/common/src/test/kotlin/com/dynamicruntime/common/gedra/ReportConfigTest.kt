package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.report.ClientReport
import com.dynamicruntime.common.gedra.report.RDEF
import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.gedra.report.ReportKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Named reports in a config bundle (issue #979): declared in source or reassembled from a stored row, carried by a
 * sandbox and a template's copy, and named by the owner-root rule (#921).
 */
class ReportConfigTest : StringSpec({
    val cxt = KdrCxt("reportConfig", KdrInstanceConfig("reportConfig", ENV.local, ENV.liveSource))

    fun clientConfig(client: String = "acme", name: String = "main", build: GedraConfigBuilder.() -> Unit) =
        gedraConfig(cxt, name, "client.$client", client, build = build)

    fun reportsOf(config: GedraConfig): List<Map<String, Any?>> = config.reports.values.map { it.toJsonMap() }

    val source = clientConfig {
        report("auditOverview", "Audit overview") {
            description = "Each site's audit."
            column("auditor", "Auditor", "form.acmeSiteAudit.auditor")
            column("total", "Total", "form.sample:expenseReport.totalAmount", ReportKind.number, rollup = ReportCombine.sum)
            groupBy = listOf("auditor")
            excludeEmpty = listOf("auditor")
        }
        report("yearlyNotes", "Yearly records") {
            column("years", "Years", "form.sample:yearly[*].year", combine = ReportCombine.list)
        }
    }

    "a config's reports survive the stored round trip, in order" {
        val entries = gedraConfigToEntries(source)
        entries.getValue(CCT.reportDef).map { it[CCT.reportId] } shouldContainExactly listOf("auditOverview", "yearlyNotes")
        val back = reassembleGedraConfig(cxt, "main", "client.acme", "acme", entries)
        back.reports.keys.toList() shouldContainExactly listOf("auditOverview", "yearlyNotes")
        reportsOf(back) shouldBe reportsOf(source)
        // A config with no reports stores no report slot.
        gedraConfigToEntries(clientConfig { }).containsKey(CCT.reportDef) shouldBe false
    }

    "a stored report with a path that means nothing still reassembles" {
        // Paths are checked against the client when the configuration loads, where a bad one costs only its report;
        // refusing it here would cost the whole stored config.
        val raw = mapOf(
            RDEF.reportId to "odd", RDEF.label to "Odd",
            RDEF.columns to listOf(mapOf(RDEF.columnId to "x", RDEF.label to "X", RDEF.path to "not a path")),
        )
        val back = reassembleGedraConfig(
            cxt, "main", "client.acme", "acme",
            mapOf(CCT.reportDef to listOf(mapOf(CCT.reportId to "odd", CCT.definition to raw))),
        )
        back.reports.getValue("odd").columns.single().path shouldBe "not a path"
    }

    "a report id declared twice in one config is refused" {
        shouldThrow<KdrException> {
            clientConfig {
                report("dup", "One") { column("a", "A", "user.email") }
                report("dup", "Two") { column("a", "A", "user.email") }
            }
        }.message!! shouldContain "declared twice"
    }

    "a client's report id is bare and a component's rooted, as every owned name is" {
        // A client may not take a rooted name: a colon reads as another owner's definition.
        val rooted = clientConfig { report("kdr:mine", "Mine") { column("a", "A", "user.email") } }
        shouldThrow<KdrException> { GedraConfigCollector().add(cxt, rooted) }.message!! shouldContain "kdr:mine"
        // A component's must be rooted under its namespace's root.
        val bareGlobal = gedraConfig(cxt, "reports", GCFG.globalNamespace) { report("bare", "Bare") { column("a", "A", "user.email") } }
        shouldThrow<KdrException> { GedraConfigCollector().add(cxt, bareGlobal) }.message!! shouldContain "'bare' is not a rooted report id"
        val otherRoot = gedraConfig(cxt, "reports", GCFG.globalNamespace) { report("abc:x", "X") { column("a", "A", "user.email") } }
        shouldThrow<KdrException> { GedraConfigCollector().add(cxt, otherRoot) }.message!! shouldContain "under the root 'abc'"
        GedraConfigCollector().add(cxt, source) shouldBe true
    }

    "a client sees the global reports and its own" {
        val global = gedraConfig(cxt, "reports", GCFG.globalNamespace) {
            report("kdr:formsByStatus", "Forms by status") { column("status", "Status", "meta.formStatus") }
        }
        val second = clientConfig(name = "more") {
            report("auditOverview", "Second declaration") { column("a", "A", "user.email") }
            report("owners", "Owners") { column("email", "Email", "user.email") }
        }
        val other = clientConfig(client = "globex") { report("globexOnly", "Globex") { column("a", "A", "user.email") } }
        val collector = GedraConfigCollector().apply { listOf(global, source, second, other).forEach { add(cxt, it) } }
        fun ids(client: String) = collector.reportsFor(client).map { it.reportId }
        // Global first, then the client's own in contribution order; no other client's.
        ids("acme") shouldContainExactly listOf("kdr:formsByStatus", "auditOverview", "yearlyNotes", "owners")
        ids("globex") shouldContainExactly listOf("kdr:formsByStatus", "globexOnly")
        ids(GID.globalClient) shouldContainExactly listOf("kdr:formsByStatus")
        // Two of a client's own configs naming one report: the first is kept (the registry says so, #980).
        collector.reportsFor("acme").first { it.reportId == "auditOverview" }.label shouldBe "Audit overview"
    }

    "a sandbox carries its parent's reports" {
        reportsOf(SandboxConfigs.rebind(source)) shouldBe reportsOf(source)
    }

    "a client built on a template gets its reports, its own replacing the template's of the same id" {
        val template = gedraConfig(cxt, "base", "client.tpl", "tpl") {
            report("overview", "Template overview") { column("a", "A", "user.email") }
            report("owners", "Owners") { column("email", "Email", "user.email") }
        }
        val own = clientConfig(client = "kid") { report("overview", "Kid overview") { column("b", "B", "user.name") } }
        val copy = ClientExtension.clones("kid", "tpl", listOf(template), listOf(own), GedraConfigOrigin.source).single()
        copy.reports.keys.toList() shouldContainExactly listOf("owners")
        // With nothing of its own, every template report comes over.
        ClientExtension.clones("kid", "tpl", listOf(template), emptyList(), GedraConfigOrigin.source).single()
            .reports shouldContainKey "overview"
    }

    "the stored slot references the definition schema" {
        val core = coreConfigTraits(cxt)
        val slot = core.configTraits.getValue(CCT.reportDef)
        slot.primaryKey shouldContainExactly listOf(CCT.reportId)
        // The slot's data type holds the definition by reference, as the workflow slot does.
        val dataType = slot.dataSchema.values.single().toString().substringAfterLast('/')
        core.defs.getValue(dataType).toString() shouldContain "${RDEF.namespace}.${RDEF.defType}"
    }
})
