package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.report.RGEN
import com.dynamicruntime.common.gedra.report.RRUN
import com.dynamicruntime.common.gedra.report.ReportMode
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.parseDate
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import com.dynamicruntime.sample.SampleTestInstances
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * A report's **typed endpoints** (issue #1088), over the sample's acme and globex reports: advertised under their
 * client with the report's own row and group types, answering what the shared run answers, taking only what the
 * path has not already fixed, and refusing whom the shared run refuses.
 *
 * Booted with response-schema validation **off**, as a real deployment has it, so that every response here is
 * checked only because a typed endpoint asks for it -- which is the enforcement the feature promises. And with env
 * auth assumed, so the whole catalog can be read beside the typed endpoints; what a caller without it is shown of
 * them is the `reportTypedCatalogTests` part below.
 *
 * Also, since issue #1736, the tests that were `ReportTypedCatalogTest`: what a caller without env auth is shown of
 * the typed report endpoints, on a context of their own.
 */
class ReportTypedEndpointTest : StringSpec({
    include(reportTypedEndpointTests)
    include(reportTypedCatalogTests)
})

/** This file's own tests, as the class describes them. */
val reportTypedEndpointTests = stringSpec {
    // An instance of its own (issue #1736): it turns response validation off and assumes env auth, which no shared instance has.
    val cxt = Startup.mkTestBootCxt(
        "reportTyped", "reportTypedEndpointTest",
        mapOf("KDR_LOAD_SAMPLE" to "true", ACFG.validateResponseSchema to false, ACFG.assumeEnvAuth to true),
        additionalComponents = listOf(SampleComponent()),
    )
    val admin = TestUser.create(cxt, "typed-admin@acme.test", level = ROLE.admin, userClient = SC.acme)
    val full = TestUser.createFullAdmin(cxt, "typed-full@example.com")
    val owner = TestUser.create(cxt, "typed-owner@acme.test", userClient = SC.acme)
    val globexOwner = TestUser.create(cxt, "typed-owner@globex.test", userClient = SC.globex)

    fun run(client: String, reportId: String) = UADEP.typedReportPath(client, reportId, UADEP.typedReportRun)
    fun aggregate(client: String, reportId: String) = UADEP.typedReportPath(client, reportId, UADEP.typedReportAggregate)

    fun audit(auditor: String) = mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to auditor, SC.findings to "closed"))
    fun expense(year: Long, perItem: Double = 10.0, count: Long = 2) = mapOf(
        GE.traitId to ST.expenseReport,
        GE.data to mapOf(ST.year to year, ST.perItemAmount to perItem, ST.itemCount to count),
    )

    fun create(user: TestUser, client: String, vararg entries: Map<String, Any?>): String =
        user.postItem(clientPath(GEP.formDocCreate, client), mapOf(GDF.entries to entries.toList()))[GDF.gedraId]
            .toOptStr().shouldNotBeNull()

    fun page(user: TestUser, path: String, args: Map<String, Any?> = emptyMap()): Map<String, Any?> = user.client.sendJsonGetRequest(path, args)
    fun itemsOf(env: Map<String, Any?>) = env[EP.items].toJsonListOfMaps()

    /** Every row of a run, walking `next` to the end. */
    fun walk(user: TestUser, path: String, args: Map<String, Any?> = emptyMap()): List<Map<String, Any?>> {
        val rows = mutableListOf<Map<String, Any?>>()
        var after: String? = null
        var pages = 0
        do {
            val env = page(user, path, args + (after?.let { mapOf(EP.after to it) } ?: emptyMap()))
            rows.addAll(itemsOf(env))
            after = env[EP.next].toOptStr()
        } while (after != null && ++pages < 100)
        return rows
    }

    val formIds = listOf(
        create(owner, SC.acme, audit("Smith"), expense(2024, 12.5, 4)),
        create(owner, SC.acme, audit("Jones"), expense(2023)),
        create(owner, SC.acme, audit("Smith"), expense(2025, 5.0, 3)),
        // No audit at all: the form with no auditor, which a grouped run puts under no value.
        create(owner, SC.acme, expense(2022)),
    ).sorted()

    "the catalog lists each report's typed endpoints under its client, with the report's own types" {
        cxt.instanceConfig.get(ACFG.validateResponseSchema) shouldBe false
        val catalog = full.getData("/schema/endpoints", mapOf(EI.client to SC.acme, EP.limit to 1000))
        val endpoints = catalog[EI.endpoints].toJsonListOfMaps().associateBy { it[EI.path].toOptStr() }
        for (reportId in listOf(SC.auditOverview, SC.expensesByYear, SC.formRoster)) {
            endpoints.keys shouldContain run(SC.acme, reportId)
            endpoints.keys shouldContain aggregate(SC.acme, reportId)
        }
        // Another client's are its own, and the shared surface has none.
        endpoints.keys shouldNotContain run(SC.globex, SC.yearlyNotes)
        full.getData("/schema/endpoints", mapOf(EP.limit to 1000))[EI.endpoints].toJsonListOfMaps().map { it[EI.path] } shouldNotContain
            run(SC.acme, SC.auditOverview)

        // The run answers in the report's row type, the aggregate in its group type, each in `$defs`.
        val defs = catalog[SCH.dDefs].toJsonMapOrEmpty()
        fun itemsRef(path: String) = endpoints.getValue(path)[EI.outputSchema].toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()[EP.items]
            .toJsonMapOrEmpty()[SCH.items].toJsonMapOrEmpty()[SCH.dRef]
        val rowType = RGEN.rowType(SC.acme, SC.auditOverview)
        itemsRef(run(SC.acme, SC.auditOverview)) shouldBe "#/${SCH.dDefs}/$rowType"
        itemsRef(aggregate(SC.acme, SC.auditOverview)) shouldBe "#/${SCH.dDefs}/${RGEN.groupType(SC.acme, SC.auditOverview)}"
        fun columnsOf(type: String, holder: String) = defs[type].toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()[holder]
            .toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()
        val columns = columnsOf(rowType, RRUN.values)
        columns.keys.toList() shouldContainExactly listOf("auditor", "findings", "year", "total", "review", "status", "owner", "ownerName")
        columns["auditor"].toJsonMapOrEmpty().let { it[SCH.type] shouldBe SCT.string; it[SCH.title] shouldBe "Auditor" }
        columns["total"].toJsonMapOrEmpty()[SCH.type] shouldBe SCT.number
        // The group type holds what the report groups by, and only what it rolls up.
        val groupType = RGEN.groupType(SC.acme, SC.auditOverview)
        columnsOf(groupType, RRUN.group).keys.toList() shouldContainExactly listOf("auditor")
        columnsOf(groupType, RRUN.values).keys.toList() shouldContainExactly listOf("year", "total")
        // A form's creation date is a moment.
        columnsOf(RGEN.rowType(SC.acme, SC.formRoster), RRUN.values)["created"].toJsonMapOrEmpty()[SCH.format] shouldBe SFMT.dateTime

        // What a typed endpoint takes: which forms to leave out, and the page. The client, the report and the mode
        // are its path.
        endpoints.getValue(run(SC.acme, SC.auditOverview))[EI.inputSchema].toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty().keys shouldBe
            setOf(RRUN.excludeEmpty, EP.limit, EP.after)

        // globex's keyed-trait report: a column that lists is an array of its kind.
        val globex = full.getData("/schema/endpoints", mapOf(EI.client to SC.globex, EP.limit to 1000))
        globex[EI.endpoints].toJsonListOfMaps().map { it[EI.path] } shouldContain run(SC.globex, SC.yearlyNotes)
        val years = globex[SCH.dDefs].toJsonMapOrEmpty()[RGEN.rowType(SC.globex, SC.yearlyNotes)].toJsonMapOrEmpty()[SCH.properties]
            .toJsonMapOrEmpty()[RRUN.values].toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()["years"].toJsonMapOrEmpty()
        years[SCH.type] shouldBe SCT.array
        years[SCH.items].toJsonMapOrEmpty()[SCH.type] shouldBe SCT.number
    }

    "a typed run walks every form once and answers what the shared run answers" {
        val rows = walk(admin, run(SC.acme, SC.auditOverview), mapOf(EP.limit to 2))
        rows.map { it[RRUN.gedraId] } shouldContainExactly formIds
        // A row is the form and its columns, and nothing else.
        rows.first().keys shouldBe setOf(RRUN.gedraId, RRUN.values)
        rows shouldBe walk(admin, UADEP.reportRun, mapOf(RRUN.reportId to SC.auditOverview))
        // The summary beside the rows is the shared run's.
        val summary = page(admin, run(SC.acme, SC.auditOverview))[EP.summary].toJsonMapOrEmpty()
        summary[RRUN.reportId] shouldBe SC.auditOverview
        summary[RRUN.mode] shouldBe ReportMode.detail.name
        summary[RRUN.client] shouldBe SC.acme
        // A typed run is a query of its own: the shared run's cursor is not a place in it.
        val sharedNext = page(admin, UADEP.reportRun, mapOf(RRUN.reportId to SC.auditOverview, EP.limit to 1))[EP.next].toOptStr().shouldNotBeNull()
        admin.expectError(EXC.badInput, run(SC.acme, SC.auditOverview), args = mapOf(EP.after to sharedNext))
        // A column that is a moment arrives as one, and passes the strict type it is checked against.
        val created = itemsOf(page(admin, run(SC.acme, SC.formRoster))).first()[RRUN.values].toJsonMapOrEmpty()["created"].toOptStr().shouldNotBeNull()
        created shouldContain "T"
        created.parseDate()
    }

    "a typed aggregate is grouped as the report declares, a row per group" {
        val groups = walk(admin, aggregate(SC.acme, SC.auditOverview))
        groups.first().keys shouldBe setOf(RRUN.group, RRUN.count, RRUN.values)
        // Jones, Smith, then the form with no auditor, under null.
        groups.map { it[RRUN.group].toJsonMapOrEmpty()["auditor"] } shouldContainExactly listOf("Jones", "Smith", null)
        groups.map { (it[RRUN.count] as Number).toInt() } shouldContainExactly listOf(1, 2, 1)
        groups[1][RRUN.values].toJsonMapOrEmpty().keys shouldBe setOf("year", "total")
        groups shouldBe walk(admin, UADEP.reportRun, mapOf(RRUN.reportId to SC.auditOverview, RRUN.aggregate to true))
        page(admin, aggregate(SC.acme, SC.auditOverview))[EP.summary].toJsonMapOrEmpty()[RRUN.groupBy] shouldBe listOf("auditor")
        // A report that groups by a year, leaving out the forms with none, and rolls a date up to a moment.
        val byYear = walk(admin, aggregate(SC.acme, SC.expensesByYear))
        byYear.map { (it[RRUN.group].toJsonMapOrEmpty()["year"] as Number).toInt() } shouldContainExactly listOf(2022, 2023, 2024, 2025)
        // A report with nothing to group by is one total row.
        walk(admin, aggregate(SC.acme, SC.formRoster)).single().let {
            it[RRUN.group] shouldBe emptyMap<String, Any?>()
            (it[RRUN.count] as Number).toInt() shouldBe formIds.size
        }
    }

    "which forms are left out may still be said; what the path fixes may not" {
        // The report excludes nothing of its own; asked to, the form with no auditor goes.
        walk(admin, run(SC.acme, SC.auditOverview), mapOf(RRUN.excludeEmpty to "auditor")).size shouldBe formIds.size - 1
        walk(admin, aggregate(SC.acme, SC.auditOverview), mapOf(RRUN.excludeEmpty to "auditor"))
            .map { it[RRUN.group].toJsonMapOrEmpty()["auditor"] } shouldContainExactly listOf("Jones", "Smith")
        admin.expectError(EXC.badInput, run(SC.acme, SC.auditOverview), args = mapOf(RRUN.excludeEmpty to "nope"))
        // The grouping is the report's -- the group type was made from it -- and the client, report and mode are the
        // path's. Each is refused, not ignored.
        admin.expectError(EXC.badInput, aggregate(SC.acme, SC.auditOverview), args = mapOf(RRUN.groupBy to "findings"))
        admin.expectError(EXC.badInput, run(SC.acme, SC.auditOverview), args = mapOf(RRUN.client to SC.acme))
        admin.expectError(EXC.badInput, run(SC.acme, SC.auditOverview), args = mapOf(RRUN.aggregate to true))
        admin.expectError(EXC.badInput, run(SC.acme, SC.auditOverview), args = mapOf(RRUN.reportId to SC.formRoster))
        // And a report the client does not have is not there to call.
        admin.expectError(EXC.notFound, run(SC.acme, "noSuchReport"))
        admin.expectError(EXC.notFound, run(SC.acme, SC.yearlyNotes))
    }

    "a client's typed endpoints are for its administrators and for one who sees every client" {
        val globexAdmin = TestUser.create(cxt, "typed-admin@globex.test", level = ROLE.admin, userClient = SC.globex)
        fun yearly(year: Long, note: String) = mapOf(GE.traitId to ST.yearly, GE.data to mapOf(ST.year to year, ST.note to note))
        create(globexOwner, SC.globex, yearly(2024, "Good year"), yearly(2023, "Slow"))

        // Its own administrator, and a list column arriving as a list.
        val own = walk(globexAdmin, run(SC.globex, SC.yearlyNotes)).single()[RRUN.values].toJsonMapOrEmpty()
        own["years"].let { (it as List<*>).map { y -> (y as Number).toInt() } } shouldContainExactly listOf(2023, 2024)
        (own["yearCount"] as Number).toInt() shouldBe 2
        // One who sees every client may call any client's.
        walk(full, run(SC.globex, SC.yearlyNotes)).size shouldBe 1
        walk(full, run(SC.acme, SC.auditOverview)).size shouldBe formIds.size
        // Another client's administrator may not, in either direction -- and is answered as for a client or a report
        // that does not exist, so the refusal says neither that the client is there nor which reports it has (#1293).
        globexAdmin.expectError(EXC.notFound, run(SC.acme, SC.auditOverview))
        admin.expectError(EXC.notFound, aggregate(SC.globex, SC.yearlyNotes))
        // Nor one who administers no client (in `public`, to whom acme's endpoint is as missing as for another client's
        // administrator), nor an ordinary user of this one (theirs to call, and refused for the role).
        TestUser.create(cxt, "typed-self@example.com", level = ROLE.admin, userClient = CL.public)
            .expectError(EXC.notFound, run(SC.acme, SC.auditOverview))
        owner.expectError(EXC.notAuthorized, run(SC.acme, SC.auditOverview))
    }

    // What "always checked" means: this node validates no response of its own accord, and a typed endpoint's is
    // refused all the same when it is not of the report's type.
    "a response not of the report's type is refused, with response validation off" {
        val schema = SchemaService.get(cxt)
        val endpoint = schema.schemaStore.endpoints.getValue("${run(SC.acme, SC.auditOverview)}:GET")
        endpoint.validateOutput shouldBe true
        val env = page(admin, run(SC.acme, SC.auditOverview), mapOf(EP.limit to 1))
        val requests = RequestService.get(cxt)
        requests.validateResponse(cxt, schema.storeFor(SC.acme), endpoint, env)
        fun withValue(column: String, value: Any?) = env + (EP.items to itemsOf(env).map {
            it + (RRUN.values to it[RRUN.values].toJsonMapOrEmpty() + (column to value))
        })
        // Text where the report says a number -- uncoerced -- and a column the report does not have.
        shouldThrow<KdrException> { requests.validateResponse(cxt, schema.storeFor(SC.acme), endpoint, withValue("total", "12")) }
            .message.orEmpty() shouldContain "items[0].values.total"
        shouldThrow<KdrException> { requests.validateResponse(cxt, schema.storeFor(SC.acme), endpoint, withValue("extra", 1L)) }
            .message.orEmpty() shouldContain "items[0].values.extra"
        // The shared run, declared and untyped, asks for no such check.
        schema.schemaStore.endpoints.getValue("${UADEP.reportRun}:GET").validateOutput shouldBe false
    }
}

/**
 * A report's typed endpoints **in the catalog a production caller reads** (issue #1128): with no env auth, a client's
 * own administrator is shown that client's typed report endpoints, with each report's row and group type -- the
 * contract #1083 made, readable by the people it is for -- and the catalog shows them to exactly those who may call
 * them.
 *
 * An instance of its own because it needs the sample's reports **without** env auth assumed: `ReportTypedEndpointTest`
 * assumes it, to read the whole catalog, and the catalog's answer to a caller without it is what is under test here.
 */
val reportTypedCatalogTests = stringSpec {
    val cxt = SampleTestInstances.default("reportTypedCatalog")
    val acmeAdmin = TestUser.create(cxt, "catalog-admin@acme.test", level = ROLE.admin, userClient = SC.acme)
    val acmeUser = TestUser.create(cxt, "catalog-user@acme.test", userClient = SC.acme)
    val globexAdmin = TestUser.create(cxt, "catalog-admin@globex.test", level = ROLE.admin, userClient = SC.globex)
    val selfAdmin = TestUser.create(cxt, "catalog-self@example.com", level = ROLE.admin, userClient = CL.public)
    val full = TestUser.createFullAdmin(cxt, "catalog-full@example.com")

    fun run(client: String, reportId: String) = UADEP.typedReportPath(client, reportId, UADEP.typedReportRun)
    fun aggregate(client: String, reportId: String) = UADEP.typedReportPath(client, reportId, UADEP.typedReportAggregate)
    val acmeReports = listOf(SC.auditOverview, SC.expensesByYear, SC.formRoster)
    val acmeTyped = acmeReports.flatMap { listOf(run(SC.acme, it), aggregate(SC.acme, it)) }

    fun endpointsOf(catalog: Map<String, Any?>) = TestUser.endpointsIn(catalog)
    fun pathsOf(catalog: Map<String, Any?>) = TestUser.pathsIn(catalog)

    /**
     * Whether [user] may call the typed endpoint at [path]: a page of it, or a refusal -- of one with no authority over
     * the client (403), or of one who may not look at that client at all, to whom its endpoint is as missing as a
     * client that does not exist (404, issue #1293).
     */
    fun mayCall(user: TestUser, path: String): Boolean {
        val env = user.client.sendJsonGetRequest(path, mapOf(EP.limit to 1))
        return when ((env[EP.status] as? Number)?.toInt()) {
            null -> env.containsKey(EP.items)
            EXC.notAuthorized, EXC.notFound -> false
            else -> throw AssertionError("Calling '$path' answered neither a page nor a refusal: $env")
        }
    }

    "a client's administrator reads its reports' typed endpoints and their types from the catalog" {
        // The premise: this node grants no env auth, so the caller is the production one the catalog confines.
        cxt.instanceConfig.get(ACFG.assumeEnvAuth) shouldNotBe true
        val catalog = acmeAdmin.catalog()
        catalog[EI.filtersAvailable] shouldBe false
        pathsOf(catalog) shouldContainAll acmeTyped
        // Each with what a run of it returns: the report's own row and group type.
        val defs = catalog[SCH.dDefs].toJsonMapOrEmpty()
        for (reportId in acmeReports) {
            defs.keys shouldContain RGEN.rowType(SC.acme, reportId)
            defs.keys shouldContain RGEN.groupType(SC.acme, reportId)
        }
        // They are all that is shown beyond the published API: the shared report endpoints, declared in the same
        // section, stay hidden, and so does another client's.
        endpointsOf(catalog).filter { it[EI.publicApi] != true }.map { it[EI.path].toOptStr() }.toSet() shouldBe acmeTyped.toSet()
        pathsOf(catalog) shouldNotContain UADEP.reports
        pathsOf(catalog) shouldNotContain UADEP.reportRun
        pathsOf(catalog) shouldNotContain run(SC.globex, SC.yearlyNotes)

        // Asking for the published API narrows to it; asking for the rest lifts nothing more.
        endpointsOf(acmeAdmin.catalog(mapOf(EI.publicApi to true))).all { it[EI.publicApi] == true } shouldBe true
        endpointsOf(acmeAdmin.catalog(mapOf(EI.publicApi to false))).filter { it[EI.publicApi] != true }.map { it[EI.path].toOptStr() }.toSet() shouldBe
            acmeTyped.toSet()

        // One endpoint looked up by itself answers the same, with its type.
        val one = acmeAdmin.catalogLookup(run(SC.acme, SC.auditOverview))
        pathsOf(one) shouldBe listOf(run(SC.acme, SC.auditOverview))
        one[SCH.dDefs].toJsonMapOrEmpty().keys shouldContain RGEN.rowType(SC.acme, SC.auditOverview)
        endpointsOf(acmeAdmin.catalogLookup(UADEP.reportRun)).shouldBeEmpty()
    }

    "the catalog shows a typed endpoint to exactly those who may call it" {
        val path = run(SC.acme, SC.auditOverview)
        // acme's sandbox has the report too, and so typed endpoints of its own. There the role is not enough: one
        // granted `admin` in the sandbox who is no administrator of acme has it withheld (issue #929), while an
        // acme administrator who opens the sandbox acts as one in it.
        val sandbox = sandboxOf(SC.acme)
        val sandboxPath = run(sandbox, SC.auditOverview)
        val granted = TestUser.create(cxt, "catalog-stranger@acme.test", level = ROLE.admin, userClient = sandbox)
        val opened = TestUser.create(cxt, "catalog-chief@acme.test", level = ROLE.admin, userClient = SC.acme)
        opened.postData(AEP.openSandbox, emptyMap())
        opened.selfClient() shouldBe sandbox

        // Shown by the lookup -- which answers for any client's endpoint -- exactly when the call is answered.
        val callers = listOf(
            "acme admin" to acmeAdmin, "acme user" to acmeUser, "globex admin" to globexAdmin, "public admin" to selfAdmin,
            "full admin" to full, "sandbox admin in name only" to granted, "acme admin in the sandbox" to opened,
        )
        for (at in listOf(path, sandboxPath)) {
            for ((who, user) in callers) {
                val shown = endpointsOf(user.catalogLookup(at)).isNotEmpty()
                if (shown != mayCall(user, at)) throw AssertionError("For the $who, '$at' is shown=$shown but callable=${!shown}.")
            }
        }
        mayCall(granted, sandboxPath) shouldBe false
        mayCall(opened, sandboxPath) shouldBe true
        // In the sandbox they oversee the sandbox, not the client they came from.
        mayCall(opened, path) shouldBe false
        mayCall(acmeAdmin, sandboxPath) shouldBe false
        pathsOf(opened.catalog()) shouldContain sandboxPath
        pathsOf(granted.catalog()) shouldNotContain sandboxPath
        mayCall(acmeAdmin, path) shouldBe true
        mayCall(full, path) shouldBe true
        mayCall(acmeUser, path) shouldBe false
        mayCall(globexAdmin, path) shouldBe false
        mayCall(selfAdmin, path) shouldBe false

        // The listing agrees: an ordinary user of the client and another client's administrator are not shown it;
        // that administrator is shown their own client's, and may not ask for this client's surface.
        pathsOf(acmeUser.catalog()) shouldNotContain path
        pathsOf(globexAdmin.catalog()) shouldNotContain path
        pathsOf(globexAdmin.catalog()) shouldContain run(SC.globex, SC.yearlyNotes)
        globexAdmin.expectError(EXC.badInput, "/schema/endpoints", args = mapOf(EI.client to SC.acme))
        // One who sees every client is shown a client's when they name it.
        pathsOf(full.catalog(mapOf(EI.client to SC.acme))) shouldContainAll acmeTyped
    }
}
