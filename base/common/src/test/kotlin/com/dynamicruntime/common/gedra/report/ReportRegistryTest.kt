package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GCEL
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GedraConfigCollector
import com.dynamicruntime.common.gedra.GedraConfigIssue
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.supportedTraits
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.parseSchemaTypes
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The report registries (issue #980), built over hand-assembled configs with no boot: each scope binds its own
 * reports, a report that does not bind costs itself alone, and a report id declared twice in a scope keeps the first.
 */
class ReportRegistryTest : StringSpec({
    val cxt = KdrCxt("reportRegistry", KdrInstanceConfig("reportRegistry", ENV.local, ENV.liveSource))

    val global = gedraConfig(cxt, "reports", GCFG.globalNamespace) {
        trait("SiteEntry", "kdr:site", setOf(GedraDataType.formDoc), "A site.") { property("city", "The city.") }
        report("kdr:sites", "Sites") { column("city", "City", "form.kdr:site.city") }
        report("kdr:broken", "Broken") { column("x", "X", "form.kdr:site.country") }
    }
    val own = gedraConfig(cxt, "main", "client.acme", "acme") {
        trait("AuditEntry", "audit", setOf(GedraDataType.formDoc), "An audit.") { property("auditor", "Who.") }
        report("audits", "Audits") { column("auditor", "Auditor", "form.audit.auditor") }
        // A client report reading a global trait the client does not support: refused in the client's scope.
        report("citySites", "Sites by city") { column("city", "City", "form.kdr:site.city") }
    }
    val more = gedraConfig(cxt, "more", "client.acme", "acme") {
        report("audits", "Audits again") { column("auditor", "Auditor", "form.audit.auditor") }
        report("owners", "Owners") { column("email", "Email", "user.email") }
    }
    val collector = GedraConfigCollector().apply { listOf(global, own, more).forEach { add(cxt, it) } }
    val types = parseSchemaTypes(collector.defs())
    val acme = ClientDef(
        clientId = "acme", name = "Acme", usageType = ClientUsageType.dev, audience = ClientAudience.internal,
        enabledEnvironments = setOf(ENV.local),
    )

    fun scopeOf(client: String?): ReportScope = ReportScope(
        ReportService.formTraitsOf(
            if (client == null) collector.traitsFor(GID.globalClient) else supportedTraits(collector, client, acme, emptySet()),
        ),
        types,
        emptyMap(),
    )

    /** The registries, with every problem captured as a trial captures them rather than refusing. */
    fun captured(): Pair<ReportRegistries, List<GedraConfigIssue>> {
        val capture = mutableListOf<GedraConfigIssue>()
        val tcxt = cxt.mkSubContext("trial", "acme").also { it.locals[GCFG.trialCaptureKey] = capture }
        val registries = buildReportRegistries(tcxt, collector, mapOf("acme" to acme), ::scopeOf, mutableListOf())
        return registries to capture
    }

    "each scope keeps the reports that bind, and each that does not is one issue" {
        val (registries, issues) = captured()
        registries.global.reports.keys.toList() shouldContainExactly listOf("kdr:sites")
        // The client sees the global reports as the global scope bound them, then its own.
        registries.forClient("acme").reports.keys.toList() shouldContainExactly listOf("kdr:sites", "audits", "owners")
        registries.forClient("acme").report("audits").shouldNotBeNull().bundle.gedraId shouldBe own.gedraId
        // A client with no reports of its own sees the global registry.
        registries.forClient("globex") shouldBe registries.global

        issues.map { it.elementId } shouldContainExactly listOf("kdr:broken", "citySites", "audits")
        issues.all { it.elementKind == GCEL.report } shouldBe true
        issues.map { it.client } shouldContainExactly listOf(GID.globalClient, "acme", "acme")
        issues[0].message shouldContain "form.kdr:site.country"
        issues[1].message shouldContain "'kdr:site' is not a trait the forms here can carry"
        issues[2].message shouldContain "declared a second time"
        issues[2].message shouldContain own.gedraId.toString()
    }

    "under the strict mode a problem refuses" {
        // Source config outside production is strict: the first report that does not bind refuses the build.
        shouldThrow<KdrException> {
            buildReportRegistries(cxt, collector, mapOf("acme" to acme), ::scopeOf, mutableListOf())
        }.message.shouldNotBeNull() shouldContain "kdr:broken"
    }

    "under off nothing is reported, and what does not bind is still left out" {
        val off = KdrCxt(
            "reportRegistryOff",
            KdrInstanceConfig("reportRegistryOff", ENV.local, ENV.liveSource).apply { put(GCFG.checkEnvVar.name, "off") },
        )
        val found = mutableListOf<GedraConfigIssue>()
        val registries = buildReportRegistries(off, collector, mapOf("acme" to acme), ::scopeOf, found)
        found.size shouldBe 0
        // There is nothing to run for a report that does not bind, so it is not there; a second declaration is
        // skipped, the first kept.
        registries.global.reports.keys.toList() shouldContainExactly listOf("kdr:sites")
        registries.forClient("acme").reports.keys.toList() shouldContainExactly listOf("kdr:sites", "audits", "owners")
        registries.forClient("acme").report("audits").shouldNotBeNull().bound.report.label shouldBe "Audits"
    }

    "a reload builds the one client against the running global registry" {
        val (running, _) = captured()
        val capture = mutableListOf<GedraConfigIssue>()
        val tcxt = cxt.mkSubContext("trial", "acme").also { it.locals[GCFG.trialCaptureKey] = capture }
        val rebuilt = buildReportRegistries(
            tcxt, collector, mapOf("acme" to acme), ::scopeOf, mutableListOf(),
            onlyClient = "acme", runningGlobal = running.global,
        )
        rebuilt.global shouldBe running.global
        rebuilt.forClient("acme").reports.keys.toList() shouldContainExactly listOf("kdr:sites", "audits", "owners")
        // The global scope is not judged again: only the client's own problems.
        capture.map { it.elementId } shouldContainExactly listOf("citySites", "audits")
    }
})
