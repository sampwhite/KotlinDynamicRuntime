package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GSORT
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe

/**
 * The forms list across clients, for an `allClients` admin (issue #668): every row carries its client (the
 * Client column reads it), and a `client` filter narrows the listing to one client. The filter is honored only
 * for a caller who sees across clients -- a client-scoped admin sending it is ignored, since their scope cannot
 * widen. The companion to #562's User column and its `user` filter.
 *
 * Two clients with a form each (acme and globex), so a cross-client listing has something to distinguish.
 */
class FormsClientFilterTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "formsClientFilter", "formsClientFilterTest", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )

    val acmeUser = TestUser.create(cxt, "cf-acme@acme.test", userClient = SC.acme)
    val globexUser = TestUser.create(cxt, "cf-globex@globex.test", userClient = SC.globex)

    val acmeDoc = acmeUser.postItem(
        clientPath(GEP.formDocCreate, SC.acme),
        mapOf(GDF.entries to listOf(mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to "CF Acme", SC.findings to "ok")))),
    )[GDF.gedraId].toOptStr()
    val globexDoc = globexUser.postItem(
        clientPath(GEP.formDocCreate, SC.globex),
        mapOf(GDF.entries to listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to "CF Globex")))),
    )[GDF.gedraId].toOptStr()

    val admin = TestUser.createFullAdmin(cxt, "cf-admin@example.com")
    fun idsSeenBy(tu: TestUser, args: Map<String, Any?> = emptyMap()) =
        tu.getItems(GEP.formDocs, args).mapNotNull { it[GDF.gedraId].toOptStr() }

    "an allClients admin sees rows across clients, each carrying its own client" {
        val rows = admin.getItems(GEP.formDocs)
        val byId = rows.associateBy { it[GDF.gedraId].toOptStr() }
        byId[acmeDoc]?.get(GDF.client) shouldBe SC.acme
        byId[globexDoc]?.get(GDF.client) shouldBe SC.globex
    }

    "filtering by client narrows the cross-client listing to that client (issue #668)" {
        idsSeenBy(admin, mapOf(EI.client to SC.acme)).let {
            it shouldContain acmeDoc
            it shouldNotContain globexDoc
        }
        idsSeenBy(admin, mapOf(EI.client to SC.globex)).let {
            it shouldContain globexDoc
            it shouldNotContain acmeDoc
        }
    }

    "an allClients admin can sort the cross-client listing by client (issue #668)" {
        // acme sorts before globex; ascending puts acme's row first, descending flips it. A caller who cannot
        // see across clients has only their own client's rows, so the backend ignores the key for them.
        idsSeenBy(admin, mapOf(GSORT.sort to GSORT.client, GSORT.sortDir to GSORT.asc)).let {
            it.indexOf(acmeDoc) shouldBeLessThan it.indexOf(globexDoc)
        }
        idsSeenBy(admin, mapOf(GSORT.sort to GSORT.client, GSORT.sortDir to GSORT.desc)).let {
            it.indexOf(globexDoc) shouldBeLessThan it.indexOf(acmeDoc)
        }
    }

    "a client-scoped admin's client filter is ignored -- their scope cannot widen" {
        // A plain admin in acme (has admin level, not allClients) can send the field, but the handler drops it.
        // Filtering by globex therefore still shows acme's own row -- a working filter would have excluded it.
        val scoped = TestUser.create(cxt, "cf-scoped@acme.test", level = ROLE.admin, userClient = SC.acme)
        idsSeenBy(scoped, mapOf(EI.client to SC.globex)).let {
            it shouldContain acmeDoc
            it shouldNotContain globexDoc
        }
    }
})
