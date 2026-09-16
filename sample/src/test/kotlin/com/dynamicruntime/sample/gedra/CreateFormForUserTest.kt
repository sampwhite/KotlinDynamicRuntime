package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Create a form for another user as a **client** administrator (issue #727), over the ordinary create surfaces
 * -- not the `allClients`-only `/admin/formDocForUser` (#672). The plain `formDoc/create` and the friendly
 * workflow `create` save each take an optional `user` (id or email): an administrator names a user in their own
 * scope and the form is created **owned by that user**, the admin left as the actor. Absent, or naming yourself,
 * is the ordinary self-create, so an ordinary user is unaffected.
 *
 * The sample gives `acme` (its own `acmeSiteAudit` trait) and `globex` (whose creation workflow collects
 * `name`), so both surfaces have something concrete to land in.
 */
class CreateFormForUserTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "createFormForUser", "createFormForUserTest", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )

    fun siteAuditEntries() = listOf(
        mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to "On Behalf", SC.findings to "seen")),
    )

    // A client administrator: ROLE.admin, no allClients, confined to their own client.
    fun acmeAdmin(email: String) = TestUser.create(cxt, email, level = ROLE.admin, userClient = SC.acme)
    val acmeCreate = clientPath(GEP.formDocCreate, SC.acme)

    "a client admin creates a form for a user in their own client" {
        val admin = acmeAdmin("cffu-admin@acme.test")
        admin.selfRoles() shouldContain ROLE.admin
        val target = TestUser.create(cxt, "cffu-target@acme.test", userClient = SC.acme)

        val item = admin.postItem(acmeCreate, mapOf(EI.user to "cffu-target@acme.test", GDF.entries to siteAuditEntries()))
        // Owned by the target user, in the admin's (== target's) client.
        item[GDF.client].toOptStr() shouldBe SC.acme
        item[GDF.userId].toOptLong() shouldBe target.userId
        val gedraId = item[GDF.gedraId].toOptStr().shouldNotBeNull()

        // End to end: the form is the target's, so the target sees it in their own listing.
        target.getItems(clientPath(GEP.formDocs, SC.acme)).mapNotNull { it[GDF.gedraId].toOptStr() } shouldContain gedraId
    }

    "the target may be named by numeric id as well as email" {
        val admin = acmeAdmin("cffu-admin-id@acme.test")
        val target = TestUser.create(cxt, "cffu-target-id@acme.test", userClient = SC.acme)
        admin.postItem(
            acmeCreate, mapOf(EI.user to target.userId.toString(), GDF.entries to siteAuditEntries()),
        )[GDF.userId].toOptLong() shouldBe target.userId
    }

    "a client admin cannot create for a user in another client" {
        // The admin's scope is their own client, so a globex user is not resolvable from acme -- out of reach
        // reads as not-found, never a cross-client create.
        val admin = acmeAdmin("cffu-cross@acme.test")
        TestUser.create(cxt, "cffu-globex@globex.test", userClient = SC.globex)
        admin.expectError(
            EXC.notFound, acmeCreate,
            data = mapOf(EI.user to "cffu-globex@globex.test", GDF.entries to siteAuditEntries()),
        )
    }

    "an allClients admin cannot create cross-client through the ordinary create surface" {
        // An allClients admin's scope is unrestricted, so it *can* resolve a user in another client -- but these
        // ordinary create endpoints build the form in the caller's own client, so a cross-client target is
        // refused here (that is what /admin/formDocForUser is for), rather than making a globex form stamped with
        // acme's workflow lineage.
        val admin = TestUser.create(
            cxt, "cffu-all@acme.test", level = ROLE.admin, capabilities = listOf(ROLE.allClients), userClient = SC.acme,
        )
        TestUser.create(cxt, "cffu-all-globex@globex.test", userClient = SC.globex)
        admin.expectError(
            EXC.badInput, acmeCreate,
            data = mapOf(EI.user to "cffu-all-globex@globex.test", GDF.entries to siteAuditEntries()),
        )
    }

    "an ordinary user cannot create a form for another user" {
        // An ordinary user's scope resolves only themselves, so naming anyone else is not-found -- they cannot
        // reach the escalation at all.
        val user = TestUser.create(cxt, "cffu-plain@acme.test", userClient = SC.acme)
        TestUser.create(cxt, "cffu-other@acme.test", userClient = SC.acme)
        user.expectError(
            EXC.notFound, acmeCreate,
            data = mapOf(EI.user to "cffu-other@acme.test", GDF.entries to siteAuditEntries()),
        )
    }

    "naming yourself is the ordinary self-create" {
        val user = TestUser.create(cxt, "cffu-self@acme.test", userClient = SC.acme)
        val item = user.postItem(acmeCreate, mapOf(EI.user to "cffu-self@acme.test", GDF.entries to siteAuditEntries()))
        item[GDF.userId].toOptLong() shouldBe user.userId
    }

    "a disabled target is refused" {
        val admin = acmeAdmin("cffu-disable@acme.test")
        val target = TestUser.create(cxt, "cffu-disabled@acme.test", userClient = SC.acme)
        admin.postItem(UADEP.userSetEnabled, mapOf(ADF.userId to target.userId, ADF.enabled to false))
        admin.expectError(
            EXC.badInput, acmeCreate,
            data = mapOf(EI.user to "cffu-disabled@acme.test", GDF.entries to siteAuditEntries()),
        )
    }

    "the friendly workflow create can be made for another user" {
        // globex's creation workflow collects `name`; a client admin runs the same `create` save with a `user`,
        // and the new form is the target's -- so the friendly surface carries the on-behalf option too (#727).
        val admin = TestUser.create(cxt, "cffu-wf-admin@globex.test", level = ROLE.admin, userClient = SC.globex)
        val target = TestUser.create(cxt, "cffu-wf-target@globex.test", userClient = SC.globex)
        val res = admin.postData(
            clientPath(GEP.workflowSave, SC.globex),
            mapOf(
                GDF.workflowId to SW.createForm, GDF.taskId to SW.identify, GDF.saveId to SW.create,
                GDF.entries to listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to "For Target"))),
                EI.user to "cffu-wf-target@globex.test",
            ),
        )
        res[WSF.saved] shouldBe true
        val item = res[WSF.item].toJsonMapOrEmpty()
        item[GDF.client].toOptStr() shouldBe SC.globex
        item[GDF.userId].toOptLong() shouldBe target.userId
    }
})
