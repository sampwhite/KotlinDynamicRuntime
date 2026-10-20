package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.report.RGEN
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * A report's typed endpoints **in the catalog a production caller reads** (issue #1128): with no env auth, a client's
 * own administrator is shown that client's typed report endpoints, with each report's row and group type -- the
 * contract #1083 made, readable by the people it is for -- and the catalog shows them to exactly those who may call
 * them.
 *
 * An instance of its own because it needs the sample's reports **without** env auth assumed: `ReportTypedEndpointTest`
 * assumes it, to read the whole catalog, and the catalog's answer to a caller without it is what is under test here.
 */
class ReportTypedCatalogTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "reportTypedCatalog", "reportTypedCatalogTest", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )
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
})
