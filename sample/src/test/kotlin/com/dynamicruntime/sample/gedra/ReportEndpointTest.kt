package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.report.REP
import com.dynamicruntime.common.gedra.report.RRUN
import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.gedra.report.ReportKind
import com.dynamicruntime.common.gedra.report.ReportMode
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import com.dynamicruntime.script.ReportDemo
import com.dynamicruntime.script.reportDemoAcmeEntries
import com.dynamicruntime.script.reportDemoGlobexEntries
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The report endpoints (issue #981), over the sample's acme and globex reports on a booted node with response-schema
 * validation on: walking a run by cursor, a walk that survives edits, deletes and creates, excluding forms without a
 * value, an aggregate run's groups and rollups, a keyed trait read several ways, and who may run what.
 */
class ReportEndpointTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "reportEndpoints", "reportEndpointTest", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )
    val admin = TestUser.create(cxt, "rpt-admin@acme.test", level = ROLE.admin, userClient = SC.acme)
    val full = TestUser.createFullAdmin(cxt, "rpt-full@example.com")
    val owner = TestUser.create(cxt, "rpt-owner@acme.test", userClient = SC.acme)
    val globexOwner = TestUser.create(cxt, "rpt-owner@globex.test", userClient = SC.globex)

    fun audit(auditor: String, findings: String = "closed") =
        mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to auditor, SC.findings to findings))

    fun expense(year: Long, perItem: Double = 10.0, count: Long = 2) = mapOf(
        GE.traitId to ST.expenseReport,
        GE.data to mapOf(ST.year to year, ST.perItemAmount to perItem, ST.itemCount to count),
    )

    fun create(user: TestUser, client: String, vararg entries: Map<String, Any?>): String =
        user.postItem(clientPath(GEP.formDocCreate, client), mapOf(GDF.entries to entries.toList()))[GDF.gedraId]
            .toOptStr().shouldNotBeNull()

    /** One page of a run: the whole envelope, since `next` and `summary` sit beside the items. */
    fun page(user: TestUser, args: Map<String, Any?>): Map<String, Any?> = user.client.sendJsonGetRequest(UADEP.reportRun, args)

    fun itemsOf(env: Map<String, Any?>) = env[EP.items].toJsonListOfMaps()
    fun summaryOf(env: Map<String, Any?>) = env[EP.summary].toJsonMapOrEmpty()

    /** Every page of a run, walking `next` to the end, with [between] run after each page. */
    fun walk(user: TestUser, args: Map<String, Any?>, between: (Int) -> Unit = {}): List<Map<String, Any?>> {
        val pages = mutableListOf<Map<String, Any?>>()
        var after: String? = null
        do {
            val env = page(user, args + (after?.let { mapOf(EP.after to it) } ?: emptyMap()))
            pages.add(env)
            after = env[EP.next].toOptStr()
            env[EP.hasMore] shouldBe (after != null)
            between(pages.size)
        } while (after != null && pages.size < 100)
        return pages
    }

    fun overview(more: Map<String, Any?> = emptyMap()) = mapOf(RRUN.reportId to SC.auditOverview) + more
    fun valuesOf(row: Map<String, Any?>) = row[RRUN.values].toJsonMapOrEmpty()
    fun number(v: Any?): Double? = (v as? Number)?.toDouble()

    val formIds = listOf(
        create(owner, SC.acme, audit("Smith"), expense(2024, 12.5, 4)),
        create(owner, SC.acme, audit("Jones"), expense(2023)),
        create(owner, SC.acme, audit("Smith", "open"), expense(2025, 5.0, 3)),
        create(owner, SC.acme, audit("Lee"), expense(2024)),
        // No audit at all: the form with no auditor.
        create(owner, SC.acme, expense(2022)),
    )

    /** The form the mid-walk test deletes. */
    var deletedId = ""

    "the client's reports are listed with how each column was bound" {
        val env = admin.client.sendJsonGetRequest(UADEP.reports)
        val report = itemsOf(env).single { it[RRUN.reportId] == SC.auditOverview }
        val columns = report[RRUN.columns].toJsonListOfMaps().associateBy { it[RRUN.columnId] }
        columns.getValue("total")[RRUN.kind] shouldBe ReportKind.number.name
        columns.getValue("total")[RRUN.rollup] shouldBe ReportCombine.sum.name
        columns.getValue("review")[RRUN.kind] shouldBe ReportKind.string.name
        columns.getValue("owner")[RRUN.multiValued] shouldBe false
        report[RRUN.groupBy] shouldBe listOf("auditor")
        report[RRUN.configName] shouldBe "${SC.acme}Client"
        summaryOf(env)[RRUN.client] shouldBe SC.acme
        summaryOf(env)[RRUN.issues].toJsonListOrEmpty().shouldBeEmpty()
    }

    "walking next returns every form exactly once, in id order, with a constant numAvailable" {
        val pages = walk(admin, overview(mapOf(EP.limit to 2)))
        val ids = pages.flatMap { itemsOf(it) }.map { it[RRUN.gedraId].toOptStr().shouldNotBeNull() }
        ids shouldBe ids.sorted()
        ids.toSet().size shouldBe ids.size
        ids.containsAll(formIds) shouldBe true
        pages.map { (it[EP.numAvailable] as Number).toInt() }.toSet() shouldBe setOf(ids.size)
        pages.last()[EP.next].shouldBeNull()
        summaryOf(pages.first())[RRUN.mode] shouldBe ReportMode.detail.name
        summaryOf(pages.first())[RRUN.scanned] shouldBe ids.size
    }

    "a row carries the derived total, the form's status and the owner, who is followed when they change" {
        fun row() = walk(admin, overview()).flatMap { itemsOf(it) }.single { it[RRUN.gedraId] == formIds[0] }
        val values = valuesOf(row())
        values["auditor"] shouldBe "Smith"
        values["year"] shouldBe 2024L
        // Computed on read from the per-item amount and the count: never stored.
        number(values["total"]) shouldBe 50.0
        values["owner"] shouldBe "rpt-owner@acme.test"
        values["status"].shouldNotBeNull()
        admin.postData(UADEP.userSetName, mapOf(ADF.userId to owner.userId, ADF.name to "Pat Owner"))
        valuesOf(row())["ownerName"] shouldBe "Pat Owner"
    }

    "forms edited, deleted and created mid-walk: each original once or not at all, the new one at the end" {
        val before = walk(admin, overview()).flatMap { itemsOf(it) }.map { it[RRUN.gedraId] as String }
        var created = ""
        // Two forms past the first page, by the walk's own order: ids made in one millisecond do not sort in the order
        // they were made. Not the form without an audit, which a later test reads.
        val ahead = before.drop(2).filter { it != formIds[4] }
        val edited = ahead[0]
        val deleted = ahead[1]
        deletedId = deleted
        val pages = walk(admin, overview(mapOf(EP.limit to 2))) { pageNo ->
            if (pageNo != 1) return@walk
            // After the first page: edit a form still to come, delete another, and create a new one.
            val patched = owner.client.sendJsonPostRequest(
                clientPath(GEP.patch, SC.acme),
                mapOf(
                    GPF.targets to mapOf(
                        GedraDataType.formDoc.name to listOf(
                            mapOf(
                                GDF.gedraId to edited,
                                GPF.edits to listOf(
                                    mapOf(GED.action to GedraEditAction.addOrReplace.name, GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to 2021L)),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            patched[EP.status] shouldBe null
            owner.deleteData(clientPath(GEP.formDoc, SC.acme), mapOf(GDF.gedraId to deleted))
            created = create(owner, SC.acme, audit("New"), expense(2026))
        }
        val walked = pages.flatMap { itemsOf(it) }.map { it[RRUN.gedraId] as String }
        walked.toSet().size shouldBe walked.size
        // Every original but the deleted one, in order, then the new one last.
        walked shouldBe before.filter { it != deleted } + created
        // The edit shows, on the one row the form appears in.
        pages.flatMap { itemsOf(it) }.single { it[RRUN.gedraId] == edited }.let { valuesOf(it)["year"] shouldBe 2021L }
    }

    "excludeEmpty drops forms without the value, and the counts say so" {
        val all = page(admin, overview(mapOf(RRUN.excludeEmpty to "")))
        val kept = page(admin, overview(mapOf(RRUN.excludeEmpty to "auditor")))
        val noAuditor = formIds[4]
        itemsOf(all).any { it[RRUN.gedraId] == noAuditor } shouldBe true
        itemsOf(kept).any { it[RRUN.gedraId] == noAuditor } shouldBe false
        val summary = summaryOf(kept)
        summary[RRUN.excludeEmpty] shouldBe listOf("auditor")
        (summary[RRUN.excluded] as Number).toInt() shouldBe 1
        kept[EP.numAvailable] shouldBe (summary[RRUN.scanned] as Number).toInt() - 1
        summaryOf(all)[RRUN.excluded] shouldBe 0
    }

    "an aggregate run: counts sum to the detail total, no value last, a rollup's sum matches the details" {
        val detail = walk(admin, overview()).flatMap { itemsOf(it) }
        val pages = walk(admin, overview(mapOf(RRUN.aggregate to true, EP.limit to 2)))
        val groups = pages.flatMap { itemsOf(it) }
        summaryOf(pages.first())[RRUN.mode] shouldBe ReportMode.aggregate.name
        groups.sumOf { (it[RRUN.count] as Number).toInt() } shouldBe detail.size
        pages.first()[EP.numAvailable] shouldBe groups.size
        val auditors = groups.map { it[RRUN.group].toJsonMapOrEmpty()["auditor"] }
        auditors.last().shouldBeNull()
        auditors.dropLast(1).map { it as String } shouldBe auditors.dropLast(1).map { it as String }.sortedBy { it.lowercase() }
        val smith = groups.single { it[RRUN.group].toJsonMapOrEmpty()["auditor"] == "Smith" }
        val smithTotal = detail.filter { valuesOf(it)["auditor"] == "Smith" }.sumOf { number(valuesOf(it)["total"]) ?: 0.0 }
        number(valuesOf(smith)["total"]) shouldBe smithTotal
        // A column with no rollup is not in a group's values.
        valuesOf(smith).containsKey("owner") shouldBe false
        // Grouped by nothing: one total row.
        val total = itemsOf(page(admin, overview(mapOf(RRUN.aggregate to true, RRUN.groupBy to ""))))
        total.size shouldBe 1
        total.single()[RRUN.count] shouldBe detail.size
    }

    "an aggregate walk keyed by a number visits every group once, in order" {
        val pages = walk(admin, overview(mapOf(RRUN.aggregate to true, RRUN.groupBy to "year", EP.limit to 1)))
        val years = pages.flatMap { itemsOf(it) }.map { (it[RRUN.group].toJsonMapOrEmpty()["year"] as Number).toLong() }
        years shouldBe years.distinct().sorted()
        pages.size shouldBe years.size
        pages.sumOf { p -> itemsOf(p).sumOf { (it[RRUN.count] as Number).toInt() } } shouldBe
            (page(admin, overview())[EP.numAvailable] as Number).toInt()
    }

    // The two focused examples (issue #1005): one built to be read grouped, one a plain listing.
    "the aggregation example groups by year: a row per year, its sums and average matching the details" {
        val run = mapOf(RRUN.reportId to SC.expensesByYear)
        // Its own defaults: grouped by year, with no need to ask.
        val env = page(admin, run + mapOf(RRUN.aggregate to true))
        summaryOf(env)[RRUN.groupBy] shouldBe listOf("year")
        summaryOf(env)[RRUN.excludeEmpty] shouldBe listOf("year")
        val detail = walk(admin, run).flatMap { itemsOf(it) }.map { valuesOf(it) }
        val groups = itemsOf(env)
        val years = groups.map { (it[RRUN.group].toJsonMapOrEmpty()["year"] as Number).toLong() }
        years shouldBe detail.map { (it["year"] as Number).toLong() }.distinct().sorted()
        for (group in groups) {
            val year = (group[RRUN.group].toJsonMapOrEmpty()["year"] as Number).toLong()
            val ofYear = detail.filter { (it["year"] as Number).toLong() == year }
            fun values(column: String) = ofYear.mapNotNull { number(it[column]) }
            (group[RRUN.count] as Number).toInt() shouldBe ofYear.size
            val rollups = valuesOf(group)
            // A year none of whose forms has a value rolls up to a blank, not to zero.
            number(rollups["total"]) shouldBe values("total").takeIf { it.isNotEmpty() }?.sum()
            number(rollups["items"]) shouldBe values("items").takeIf { it.isNotEmpty() }?.sum()
            number(rollups["itemPrice"]) shouldBe values("itemPrice").takeIf { it.isNotEmpty() }?.let { it.sum() / it.size }
            // The grouped-by column has no rollup, so it is in the group, not the values.
            rollups.containsKey("year") shouldBe false
        }
        // A date rolls up too: the latest audit activity, for a year with an audited form.
        groups.any { valuesOf(it)["lastAudited"] is String } shouldBe true
    }

    "the listing example is a row per form with nothing rolled up" {
        val run = mapOf(RRUN.reportId to SC.formRoster)
        val env = page(admin, run)
        summaryOf(env)[RRUN.groupBy] shouldBe emptyList<String>()
        summaryOf(env)[RRUN.columns].toJsonListOfMaps().none { it.containsKey(RRUN.rollup) } shouldBe true
        val row = itemsOf(env).first { it[RRUN.gedraId] == formIds[0] }
        valuesOf(row).let {
            (it["created"] as String).startsWith("20") shouldBe true
            it["owner"] shouldBe "rpt-owner@acme.test"
            it["auditor"] shouldBe "Smith"
            it["status"].shouldNotBeNull()
        }
        // Aggregated, a report with no grouping is the one total row, with no values to roll up.
        val total = itemsOf(page(admin, run + mapOf(RRUN.aggregate to true))).single()
        total[RRUN.count] shouldBe (env[EP.numAvailable] as Number).toInt()
        valuesOf(total).isEmpty() shouldBe true
    }

    "a keyed trait's column: every year, how many, the latest, and a blank for a form without the chosen key" {
        fun yearly(year: Long, note: String) = mapOf(GE.traitId to ST.yearly, GE.data to mapOf(ST.year to year, ST.note to note))
        val both = create(globexOwner, SC.globex, yearly(2024, "Good year"), yearly(2023, "Slow"))
        val older = create(globexOwner, SC.globex, yearly(2022, "First"))
        val rows = walk(full, mapOf(RRUN.reportId to SC.yearlyNotes, RRUN.client to SC.globex))
            .flatMap { itemsOf(it) }.associateBy { it[RRUN.gedraId] }
        valuesOf(rows.getValue(both)).let {
            it["years"] shouldBe listOf(2023L, 2024L)
            it["yearCount"] shouldBe 2L
            it["latest"] shouldBe 2024L
            it["note2024"] shouldBe "Good year"
        }
        valuesOf(rows.getValue(older)).let {
            it["years"] shouldBe listOf(2022L)
            it["note2024"].shouldBeNull()
        }
    }

    "who may run what: own client, any for an allClients administrator, none for the rest" {
        page(admin, overview())[EP.items].shouldNotBeNull()
        admin.expectError(EXC.notAuthorized, UADEP.reportRun, args = mapOf(RRUN.reportId to SC.yearlyNotes, RRUN.client to SC.globex))
        page(full, overview(mapOf(RRUN.client to SC.acme)))[EP.numAvailable] shouldBe page(admin, overview())[EP.numAvailable]
        val selfAdmin = TestUser.create(cxt, "rpt-self@example.com", level = ROLE.admin, userClient = CL.public)
        selfAdmin.expectError(EXC.notAuthorized, UADEP.reportRun, args = overview())
        owner.expectError(EXC.notAuthorized, UADEP.reportRun, args = overview())
        owner.expectError(EXC.notAuthorized, UADEP.reports)
    }

    "a client with more forms than the scan limit is refused, naming the variable" {
        cxt.instanceConfig.put(REP.scanLimitEnvVar.name, "2")
        try {
            admin.expectError(EXC.badInput, UADEP.reportRun, args = overview())[EP.errorMessage].toOptStr()
                .shouldNotBeNull() shouldContain REP.scanLimitEnvVar.name
        } finally {
            cxt.instanceConfig.put(REP.scanLimitEnvVar.name, null)
        }
        page(admin, overview())[EP.items].shouldNotBeNull()
    }

    // Late: it gives acme users organizations for the rest of the boot, which the tests after it do not depend on.
    "an administrator confined to an organization reads only its forms and the client's own" {
        val north = TestUser.create(cxt, "rpt-north-admin@acme.test", level = ROLE.admin, userClient = SC.acme)
        val south = TestUser.create(cxt, "rpt-south@acme.test", userClient = SC.acme)
        full.postData(UADEP.userSetOrg, mapOf(ADF.userId to north.userId, ADF.org to "north"))
        full.postData(UADEP.userSetOrg, mapOf(ADF.userId to south.userId, ADF.org to "south"))
        val southForm = create(south, SC.acme, audit("South"), expense(2024))
        fun ids(user: TestUser) = walk(user, overview()).flatMap { itemsOf(it) }.map { it[RRUN.gedraId] }
        // The client's own forms (no organization) are every administrator's; another organization's are not.
        ids(north).containsAll(formIds - deletedId) shouldBe true
        ids(north).contains(southForm) shouldBe false
        ids(admin).contains(southForm) shouldBe true
        walk(full, overview(mapOf(RRUN.client to SC.acme))).flatMap { itemsOf(it) }.any { it[RRUN.gedraId] == southForm } shouldBe true
    }

    "an unknown report is a 404, an unknown column a 400, and another run's cursor a 400" {
        admin.expectError(EXC.notFound, UADEP.reportRun, args = mapOf(RRUN.reportId to "noSuchReport"))
        admin.expectError(EXC.badInput, UADEP.reportRun, args = overview(mapOf(RRUN.excludeEmpty to "nope")))
        admin.expectError(EXC.badInput, UADEP.reportRun, args = overview(mapOf(RRUN.aggregate to true, RRUN.groupBy to "nope")))
        // groupBy outside an aggregate run says so rather than being ignored.
        admin.expectError(EXC.badInput, UADEP.reportRun, args = overview(mapOf(RRUN.groupBy to "auditor")))
        // A detail cursor in an aggregate run is another query's -- even an aggregate grouping by nothing, whose query
        // differs from the detail run's by its mode alone.
        val detailNext = page(admin, overview(mapOf(EP.limit to 1)))[EP.next].toOptStr().shouldNotBeNull()
        for (grouping in listOf("auditor", "")) {
            admin.expectError(
                EXC.badInput, UADEP.reportRun,
                args = overview(mapOf(RRUN.aggregate to true, RRUN.groupBy to grouping, EP.after to detailNext)),
            )[EP.errorMessage].toOptStr().shouldNotBeNull() shouldContain "different query"
        }
    }
    // Last: it adds the demo's forms. The probe names the sample's traits by literal (it cannot see this module), so
    // this is what fails when the two drift apart.
    "the probe's demo entries are ones the sample accepts, and give the examples something to show" {
        ReportDemo.acme shouldBe SC.acme
        ReportDemo.globex shouldBe SC.globex
        val before = (page(admin, mapOf(RRUN.reportId to SC.formRoster))[EP.numAvailable] as Number).toInt()
        for (n in 0 until ReportDemo.acmeForms) create(owner, SC.acme, *reportDemoAcmeEntries(n).toTypedArray())
        for (n in 0 until ReportDemo.globexForms) create(globexOwner, SC.globex, *reportDemoGlobexEntries(n).toTypedArray())
        // Enough for a second page of a listing at the page size a view uses, and a group per year and per auditor.
        val roster = walk(admin, mapOf(RRUN.reportId to SC.formRoster, EP.limit to 25))
        (roster.first()[EP.numAvailable] as Number).toInt() shouldBe before + ReportDemo.acmeForms
        (roster.size >= 2) shouldBe true
        val demo = (0 until ReportDemo.acmeForms).map { reportDemoAcmeEntries(it) }
        // Some with an audit and some without, so the no-auditor group and `excludeEmpty` both have something to show.
        val unaudited = demo.count { entries -> entries.none { it[GE.traitId] == SC.siteAudit } }
        (unaudited in 1 until demo.size) shouldBe true
        val byYear = itemsOf(page(admin, mapOf(RRUN.reportId to SC.expensesByYear, RRUN.aggregate to true)))
        byYear.map { (it[RRUN.group].toJsonMapOrEmpty()["year"] as Number).toLong() }.containsAll(ReportDemo.years) shouldBe true
        val byAuditor = itemsOf(page(admin, overview(mapOf(RRUN.aggregate to true))))
        byAuditor.mapNotNull { it[RRUN.group].toJsonMapOrEmpty()["auditor"] as? String }.containsAll(ReportDemo.auditors) shouldBe true
    }
})
