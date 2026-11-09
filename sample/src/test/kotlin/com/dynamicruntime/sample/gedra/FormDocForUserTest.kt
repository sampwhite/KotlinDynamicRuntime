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
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import com.dynamicruntime.sample.SampleTestInstances
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Create a form document on behalf of another user (issue #672 Slice 3): an `allClients` admin names a user (by
 * email or numeric id) and the form is created **owned by that user, in that user's client**, validated against
 * that client's schema -- while the admin stays the actor. The everyday create surface is unchanged; this is the
 * separate `/admin/…` path, gated `admin` + `allClients`, so a scoped admin cannot reach it, and the admin may
 * not create for themselves.
 *
 * The sample gives `acme` (its own `acmeSiteAudit` trait) and `globex`, so ownership and per-client validation
 * have something concrete to land in.
 *
 * Also, since issue #1736, the tests that were `CreateFormForUserTest`: creating a form for another user, on a context of
 * their own.
 */
class FormDocForUserTest : StringSpec({
    include(formDocForUserTests)
    include(createFormForUserTests)
})

/** This file's own tests, as the class describes them. */
private val formDocForUserTests = stringSpec {
    val cxt = SampleTestInstances.default("formDocForUser")

    fun siteAuditEntries() = listOf(
        mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to "On Behalf", SC.findings to "seen")),
    )

    "an allClients admin creates a form owned by the named user in that user's client" {
        val admin = TestUser.createFullAdmin(cxt, "fdfu-admin@example.com")
        val target = TestUser.create(cxt, "fdfu-target@acme.test", userClient = SC.acme)

        val item = admin.postItem(GEP.adminFormDocForUser, mapOf(EI.user to "fdfu-target@acme.test", GDF.entries to siteAuditEntries()))
        // Owned by the target user, in the target's client -- not the admin or the admin's client.
        item[GDF.client].toOptStr() shouldBe SC.acme
        item[GDF.userId].toOptLong() shouldBe target.userId
        val gedraId = item[GDF.gedraId].toOptStr()

        // End to end: the form is the target's, so the target sees it in their own listing.
        target.getItems(GEP.formDocs).mapNotNull { it[GDF.gedraId].toOptStr() } shouldContain gedraId
    }

    "the user may be named by numeric id as well as email" {
        val admin = TestUser.createFullAdmin(cxt, "fdfu-admin-id@example.com")
        val target = TestUser.create(cxt, "fdfu-target-id@acme.test", userClient = SC.acme)

        val item = admin.postItem(
            GEP.adminFormDocForUser,
            mapOf(EI.user to target.userId.toString(), GDF.entries to siteAuditEntries()),
        )
        item[GDF.userId].toOptLong() shouldBe target.userId
    }

    "entries are validated against the target user's client, not the caller's" {
        // Same caller, same entry, two targets -- the discriminating test. An admin **in acme** sends acme's own
        // `acmeSiteAudit` trait: accepted for an acme user, refused for a globex user (globex does not carry that
        // trait). If the create validated against the *caller's* client (acme, which supports it) both would pass,
        // so the refusal proves the governing client is the **target's**.
        val admin = TestUser.create(
            cxt, "fdfu-acme-admin@acme.test", level = ROLE.admin, capabilities = listOf(ROLE.allClients), userClient = SC.acme,
        )
        TestUser.create(cxt, "fdfu-acme-user@acme.test", userClient = SC.acme)
        TestUser.create(cxt, "fdfu-globex-user@globex.test", userClient = SC.globex)

        admin.postItem(
            GEP.adminFormDocForUser, mapOf(EI.user to "fdfu-acme-user@acme.test", GDF.entries to siteAuditEntries()),
        )[GDF.client].toOptStr() shouldBe SC.acme
        admin.expectError(
            EXC.badInput,
            GEP.adminFormDocForUser,
            data = mapOf(EI.user to "fdfu-globex-user@globex.test", GDF.entries to siteAuditEntries()),
        )
    }

    "an allClients admin may not create a form for themselves" {
        val admin = TestUser.createFullAdmin(cxt, "fdfu-self@example.com")
        admin.expectError(
            EXC.badInput,
            GEP.adminFormDocForUser,
            data = mapOf(EI.user to "fdfu-self@example.com", GDF.entries to siteAuditEntries()),
        )
    }

    "naming a user who does not exist is a 404" {
        val admin = TestUser.createFullAdmin(cxt, "fdfu-absent@example.com")
        admin.expectError(
            EXC.notFound,
            GEP.adminFormDocForUser,
            data = mapOf(EI.user to "nobody@nowhere.test", GDF.entries to siteAuditEntries()),
        )
    }

    "a disabled user is not a valid target" {
        // `resolveUserRef` finds a disabled account (ids stay resolvable), but it cannot log in to see or finish
        // the form, so the create refuses it -- as every administrative user edit does.
        val admin = TestUser.createFullAdmin(cxt, "fdfu-disable-admin@example.com")
        val target = TestUser.create(cxt, "fdfu-disabled@acme.test", userClient = SC.acme)
        admin.postItem(ADEP.userSetEnabled, mapOf(ADF.userId to target.userId, ADF.enabled to false))
        admin.expectError(
            EXC.badInput,
            GEP.adminFormDocForUser,
            data = mapOf(EI.user to "fdfu-disabled@acme.test", GDF.entries to siteAuditEntries()),
        )
    }

    "a scoped administrator without allClients is refused" {
        val scoped = TestUser.create(cxt, "fdfu-scoped@acme.test", level = ROLE.admin, userClient = SC.acme)
        scoped.selfRoles() shouldNotContain ROLE.allClients
        scoped.expectError(
            EXC.notAuthorized,
            GEP.adminFormDocForUser,
            data = mapOf(EI.user to "fdfu-scoped@acme.test", GDF.entries to siteAuditEntries()),
        )
    }
}

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
private val createFormForUserTests = stringSpec {
    val cxt = SampleTestInstances.default("createFormForUser")

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
        // These ordinary create endpoints build the form in the caller's own client, so a cross-client target is
        // refused (that is what /admin/formDocForUser is for), rather than making a globex form stamped with acme's
        // workflow lineage. On acme's own endpoint the path confines even an allClients admin's scope to acme (issue
        // #1575), so a globex user is not found there, as for an acme administrator; on the shared endpoint the
        // scope is unrestricted, resolves the user, and the create refuses the other client.
        val admin = TestUser.create(
            cxt, "cffu-all@acme.test", level = ROLE.admin, capabilities = listOf(ROLE.allClients), userClient = SC.acme,
        )
        TestUser.create(cxt, "cffu-all-globex@globex.test", userClient = SC.globex)
        val forGlobex = mapOf(EI.user to "cffu-all-globex@globex.test", GDF.entries to siteAuditEntries())
        admin.expectError(EXC.notFound, acmeCreate, data = forGlobex)
        admin.expectError(EXC.badInput, GEP.formDocCreate, data = forGlobex)
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
                GDF.entries to listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.nameField to "For Target"))),
                EI.user to "cffu-wf-target@globex.test",
            ),
        )
        res[WSF.saved] shouldBe true
        val item = res[WSF.item].toJsonMapOrEmpty()
        item[GDF.client].toOptStr() shouldBe SC.globex
        item[GDF.userId].toOptLong() shouldBe target.userId
    }
}
