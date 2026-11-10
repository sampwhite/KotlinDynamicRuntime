package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientCreate
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.report.RCR
import com.dynamicruntime.common.gedra.report.RDEF
import com.dynamicruntime.common.gedra.report.RRUN
import com.dynamicruntime.common.gedra.report.ReportChartKind
import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.gedra.report.ReportEdit
import com.dynamicruntime.common.gedra.report.ReportPathGroupKind
import com.dynamicruntime.common.gedra.report.ReportService
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.userHasLabel
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.layout
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Making a report from simple choices (issue #1492): the paths offered, each bound against the client; a report made
 * from them in the client's `reports` configuration, which then runs; a keyed trait's entry named by its key; what is
 * refused; removing one; and a sandbox's drafts. A client per case, on the shared instance.
 */
class ReportCreateTest : StringSpec({
    val cxt = TestInstances.default("reportCreate1492")
    val svc = GedraConfigService.get(cxt)

    fun defineClient(client: String, sandbox: Boolean = false) {
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 14920L }
        svc.writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = sandbox, userLabels = listOf("reviewer"),
                    ),
                )
                cfact("talkApproved", "Talk", "The talk was approved.")
                trait("TalkEntry", "talk", setOf(GedraDataType.formDoc), "A talk.") {
                    // A name, which names the trait on the Reports page as on every other (issues #1520, #1534), and the
                    // introduction under it, which does not.
                    layout(label = "## Talk details", description = "Who it is for, and when.") {}
                    property("level", "Who it is for.") {
                        option("beginner", "Beginners")
                        option("advanced", "Experts")
                    }
                    property("minutes", "How long it runs.") { type = SCT.integer }
                    property("heldOn", "The day it is held.") { dayOnlyDate() }
                    property("room", "Where it is held.") {
                        type = SCT.kObject
                        property("building", "The building.")
                    }
                }
                trait("YearlyEntry", "yearly", setOf(GedraDataType.formDoc), "A note a year.") {
                    primaryKey("year")
                    property("year", "The year.", required = true) { type = SCT.integer }
                    property("note", "What happened.")
                }
                workflow("review", WfEntry.normal) {
                    label = "Review"
                    task("record", "Record the talk") { trait("talk") }
                    task("approve", "Approve the talk") {
                        approval("talkApproved", "Read it.", "Approve")
                        function(userHasLabel { label = "reviewer" })
                    }
                }
            },
        )
        svc.publish(setup, listOf(GedraId.of(GedraConfigType.configDoc, client, "main")))
        GedraConfigReload.reloadClient(cxt, client)
    }

    fun admin(client: String): TestUser = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)

    fun column(label: String, path: String, extra: Map<String, Any?> = emptyMap()) = mapOf(RDEF.label to label, RDEF.path to path) + extra

    fun create(user: TestUser, label: String, columns: List<Map<String, Any?>>, extra: Map<String, Any?> = emptyMap()) =
        user.postData(UADEP.reportCreate, mapOf(RDEF.label to label, RDEF.columns to columns) + extra)

    "the paths offered are those that bind, grouped by where the value comes from" {
        val client = "reportPaths1492"
        defineClient(client)
        val groups = admin(client).getItem(UADEP.reportPaths)[RCR.groups].toJsonListOfMaps()
        groups.map { it[RCR.source] to it[RCR.sourceId] } shouldBe listOf(
            ReportPathGroupKind.form.name to null,
            ReportPathGroupKind.trait.name to "talk",
            ReportPathGroupKind.trait.name to "yearly",
            ReportPathGroupKind.workflow.name to "review",
        )
        // Each trait named as every page names it (issue #1520): by its heading's first line, else its id in words.
        groups.map { it[RRUN.label] } shouldBe listOf("The form", "Talk details", "Yearly", "Review")
        fun paths(i: Int) = groups[i][RCR.paths].toJsonListOfMaps().associateBy { it[RRUN.path] }
        // The review's approval carries the `review` keyword, so the form's calls to action are offered (issue #1600).
        paths(0).keys shouldBe setOf("meta.createdAt", "meta.updatedAt", "meta.formStatus", "meta.ctaKeywords", "user.email", "user.name")
        // A nested object's fields are offered by their own paths, labeled under it.
        val talk = paths(1)
        talk.keys shouldBe setOf("form.talk.level", "form.talk.minutes", "form.talk.heldOn", "form.talk.room.building")
        talk.mapValues { it.value[RRUN.kind] } shouldBe mapOf(
            "form.talk.level" to "string", "form.talk.minutes" to "number", "form.talk.heldOn" to "date",
            "form.talk.room.building" to "string",
        )
        talk.getValue("form.talk.heldOn")[RRUN.label] shouldBe "Held on"
        talk.getValue("form.talk.room.building")[RRUN.label] shouldBe "Room: Building"
        // A keyed trait's every entry, with its key, which a column naming one entry gives a value for.
        val note = paths(2).getValue("form.yearly[*].note")
        note[RRUN.multiValued] shouldBe true
        note[RCR.keyFields].toJsonListOfMaps().single().let { it[RCR.field] to it[RRUN.kind] } shouldBe ("year" to "number")
        paths(3).mapValues { it.value[RRUN.label] } shouldBe mapOf(
            "workflow.review.category" to "Where it stands",
            // And its next task and that task's keyword; it sets no CTA filter, so whom it waits on is not offered.
            "workflow.review.ctaTask" to "Next task",
            "workflow.review.ctaKeyword" to "Call to action",
            "workflow.review.approval[approve].approved" to "Approve the talk: approved",
            "workflow.review.approval[approve].approvedAt" to "Approve the talk: approved on",
        )
    }

    "a row-per-form report is made in the reports configuration, and runs" {
        val client = "reportRoster1492"
        defineClient(client)
        val chief = admin(client)
        chief.client.sendJsonGetRequest(UADEP.reports, emptyMap())[EP.summary].toJsonMapOrEmpty().let {
            it[RCR.canCreate] shouldBe true
            it[RCR.createsDraft] shouldBe false
        }
        chief.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(mapOf(GE.traitId to "talk", GE.data to mapOf("minutes" to 30)))))
        val made = create(chief, "Talk roster", listOf(column("Length", "form.talk.minutes"), column("Owner", "user.email")))
        made[RRUN.reportId] shouldBe "talkRoster"
        made[CIT.configName] shouldBe ClientCreate.starterReportsConfig
        made[CPY.mode] shouldBe EDM.live
        val report = ReportService.get(cxt).forClient(client).report("talkRoster")!!.bound.report
        report.columns.map { it.columnId to it.path } shouldBe listOf("length" to "form.talk.minutes", "owner" to "user.email")
        report.groupBy shouldBe emptyList()
        val rows = chief.getItems(UADEP.reportRun, mapOf(RRUN.reportId to "talkRoster"))
        rows.single()[RRUN.values].toJsonMapOrEmpty() shouldBe mapOf("length" to 30L, "owner" to "chief@${client.lowercase()}.test")
        // The same name again is the same id, which the client already has.
        chief.expectError(
            EXC.badInput, UADEP.reportCreate,
            mapOf(RDEF.label to "Talk roster", RDEF.columns to listOf(column("Length", "form.talk.minutes"))),
        )
    }

    "a totals report groups by one column, combines the others, and names a keyed trait's entry by its key" {
        val client = "reportTotals1492"
        defineClient(client)
        val chief = admin(client)
        create(
            chief, "Talks by level",
            listOf(
                column("Level", "form.talk.level", mapOf(RCR.groupedBy to true)),
                column("Minutes", "form.talk.minutes", mapOf(RDEF.rollup to ReportCombine.sum.name, RCR.mustHaveValue to true)),
                column(
                    "Years noted", "form.yearly[*].note",
                    mapOf(RDEF.combine to ReportCombine.count.name, RDEF.rollup to ReportCombine.sum.name),
                ),
                column("Note for 2024", "form.yearly[*].note", mapOf(RCR.key to listOf("2024"))),
            ),
            mapOf(RDEF.description to "How long, per level.", RDEF.history to true),
        )
        val report = ReportService.get(cxt).forClient(client).report("talksByLevel")!!.bound.report
        report.columns.map { it.columnId to it.path } shouldBe listOf(
            "level" to "form.talk.level", "minutes" to "form.talk.minutes", "yearsNoted" to "form.yearly[*].note",
            "noteFor2024" to "form.yearly[2024].note",
        )
        report.columns.map { it.rollup } shouldBe listOf(null, ReportCombine.sum, ReportCombine.sum, null)
        report.groupBy shouldBe listOf("level")
        report.excludeEmpty shouldBe listOf("minutes")
        report.history shouldBe true
        report.description shouldBe "How long, per level."
        // A second report goes into the same configuration, beside the first.
        create(chief, "Rooms", listOf(column("Building", "form.talk.room.building")))
        svc.readLatest(cxt.mkSubContext("read", client), GedraId.of(GedraConfigType.configDoc, client, ClientCreate.starterReportsConfig))!!
            .entriesBySlot()[CCT.reportDef].orEmpty().map { it[CCT.reportId] } shouldBe listOf("talksByLevel", "rooms")
    }

    "a totals report opens on the chart asked for, measuring the column marked or the count; one that cannot be drawn is refused" {
        val client = "reportChart1773"
        defineClient(client)
        val chief = admin(client)
        val level = column("Level", "form.talk.level", mapOf(RCR.groupedBy to true))
        fun minutes(rollup: ReportCombine, charted: Boolean = true) = column(
            "Minutes", "form.talk.minutes", mapOf(RDEF.rollup to rollup.name) + if (charted) mapOf(RCR.charted to true) else emptyMap(),
        )
        create(chief, "Minutes by level", listOf(level, minutes(ReportCombine.sum)), mapOf(RDEF.chart to ReportChartKind.pie.name))
        create(
            chief, "Talks by level", listOf(level, minutes(ReportCombine.avg, charted = false)),
            mapOf(RDEF.chart to ReportChartKind.bar.name),
        )
        val reports = ReportService.get(cxt).forClient(client)
        reports.report("minutesByLevel")!!.bound.report.chart!!.let { (it.kind to it.measure) shouldBe (ReportChartKind.pie to "minutes") }
        reports.report("talksByLevel")!!.bound.report.chart!!.let { (it.kind to it.measure) shouldBe (ReportChartKind.bar to null) }
        // The listing tells the page, which opens the report's grouped run on it.
        val listed = chief.getItems(UADEP.reports, emptyMap()).associateBy { it[RRUN.reportId] }
        listed.getValue("minutesByLevel")[RRUN.chart].toJsonMapOrEmpty() shouldBe
            mapOf(RDEF.kind to ReportChartKind.pie.name, RDEF.measure to "minutes")

        fun refused(label: String, columns: List<Map<String, Any?>>, chart: String?) = chief.expectError(
            EXC.badInput, UADEP.reportCreate,
            mapOf(RDEF.label to label, RDEF.columns to columns) + (chart?.let { mapOf(RDEF.chart to it) } ?: emptyMap()),
        )[EP.errorMessage].toString()
        // A pie of an average makes no whole; the binding says so, as it does of a declared one (issue #1771).
        refused("Average pie", listOf(level, minutes(ReportCombine.avg)), ReportChartKind.pie.name) shouldContain "only a count or a sum"
        refused("Ungrouped", listOf(column("Length", "form.talk.minutes")), ReportChartKind.bar.name) shouldContain "groups by nothing"
        refused("No chart", listOf(level, minutes(ReportCombine.sum)), null) shouldContain "no chart is asked for"
        refused("Two measures", listOf(level, minutes(ReportCombine.sum), column("Again", "form.talk.minutes",
            mapOf(RDEF.rollup to ReportCombine.max.name, RCR.charted to true))), ReportChartKind.bar.name) shouldContain "2 are marked"
        // Not a kind of chart: the endpoint's own options refuse it, and so does the create behind it, called directly.
        refused("Line", listOf(level), "line")
        shouldThrow<KdrException> { ReportEdit.chartOf("line", emptyList()) }.message shouldContain "There is no chart 'line'"
        ReportEdit.chartOf("", emptyList()) shouldBe null
        ReportService.get(cxt).forClient(client).reports.keys.filter { !it.contains(':') }.toSet() shouldBe
            setOf("minutesByLevel", "talksByLevel")
    }

    "what will not bind is refused in the server's words, and nothing is made" {
        val client = "reportRefused1492"
        defineClient(client)
        val chief = admin(client)
        fun refused(label: String, columns: List<Map<String, Any?>>) =
            chief.expectError(EXC.badInput, UADEP.reportCreate, mapOf(RDEF.label to label, RDEF.columns to columns))
        refused("", listOf(column("Length", "form.talk.minutes")))
        refused("Empty", emptyList())
        refused("Summed text", listOf(column("Level", "form.talk.level",
            mapOf(RDEF.rollup to ReportCombine.sum.name))))[EP.errorMessage].toString() shouldContain "rolls up by sum"
        refused("Keyed plain", listOf(column("Level", "form.talk.level", mapOf(RCR.key to listOf("x")))))
        refused("Grouped list", listOf(column("Notes", "form.yearly[*].note", mapOf(RCR.groupedBy to true))))
        ReportService.get(cxt).forClient(client).reports.keys.filter { !it.contains(':') } shouldBe emptyList()
        val reportsId = GedraId.of(GedraConfigType.configDoc, client, ClientCreate.starterReportsConfig)
        svc.readLatest(cxt.mkSubContext("read", client), reportsId) shouldBe null
    }

    "a report the client stores is removed from where it is; one it does not store is refused, saying where it is" {
        val client = "reportRemove1492"
        defineClient(client)
        val chief = admin(client)
        create(chief, "Talk roster", listOf(column("Length", "form.talk.minutes")))
        create(chief, "Rooms", listOf(column("Building", "form.talk.room.building")))
        val removed = chief.postData(UADEP.reportRemove, mapOf(RRUN.reportId to "talkRoster"))
        removed[CIT.configName] shouldBe ClientCreate.starterReportsConfig
        removed[CPY.mode] shouldBe EDM.live
        ReportService.get(cxt).forClient(client).report("talkRoster") shouldBe null
        (ReportService.get(cxt).forClient(client).report("rooms") != null) shouldBe true
        chief.expectError(EXC.notFound, UADEP.reportRemove, mapOf(RRUN.reportId to "talkRoster"))
        // A global report is the deployment's, declared in source code.
        ReportService.get(cxt).forClient(client).reports.keys.firstOrNull { it.contains(':') }?.let { global ->
            chief.expectError(EXC.badInput, UADEP.reportRemove, mapOf(RRUN.reportId to global))[EP.errorMessage].toString() shouldContain
                "declared in code"
        }
    }

    "a client with a sandbox makes a report as a draft, which only its sandbox runs, and its page leads there" {
        val client = "reportSandbox1492"
        defineClient(client, sandbox = true)
        val chief = admin(client)
        chief.client.sendJsonGetRequest(UADEP.reports, emptyMap())[EP.summary].toJsonMapOrEmpty().let {
            it[RCR.canCreate] shouldBe false
            it[RCR.createFrom] shouldBe sandboxOf(client)
        }
        ReportEdit.offer(cxt, sandboxOf(client)) shouldBe mapOf(RCR.canCreate to true, RCR.createsDraft to true)
        val made = create(chief, "Talk roster", listOf(column("Length", "form.talk.minutes")), mapOf(CPY.mode to EDM.draft))
        made[CPY.mode] shouldBe EDM.draft
        ReportService.get(cxt).forClient(client).report("talkRoster") shouldBe null
        (ReportService.get(cxt).forClient(sandboxOf(client)).report("talkRoster") != null) shouldBe true
        // And removes one as a draft too.
        chief.postData(UADEP.reportRemove, mapOf(RRUN.reportId to "talkRoster", CPY.mode to EDM.draft))[CPY.mode] shouldBe EDM.draft
        ReportService.get(cxt).forClient(sandboxOf(client)).report("talkRoster") shouldBe null
    }
})
