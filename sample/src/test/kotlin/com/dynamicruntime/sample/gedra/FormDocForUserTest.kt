package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
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
 */
class FormDocForUserTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "formDocForUser", "formDocForUserTest", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )

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
        // A trait acme does not support is refused -- the create runs bound to acme, so acme's schema is what
        // checks it, even though the caller is an allClients admin whose own client is the default.
        val admin = TestUser.createFullAdmin(cxt, "fdfu-badtrait@example.com")
        TestUser.create(cxt, "fdfu-badtrait-target@acme.test", userClient = SC.acme)
        admin.expectError(
            EXC.badInput,
            GEP.adminFormDocForUser,
            data = mapOf(
                EI.user to "fdfu-badtrait-target@acme.test",
                GDF.entries to listOf(mapOf(GE.traitId to "noSuchTraitAnywhere", GE.data to emptyMap<String, Any?>())),
            ),
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

    "a scoped administrator without allClients is refused" {
        val scoped = TestUser.create(cxt, "fdfu-scoped@acme.test", level = ROLE.admin, userClient = SC.acme)
        scoped.selfRoles() shouldNotContain ROLE.allClients
        scoped.expectError(
            EXC.notAuthorized,
            GEP.adminFormDocForUser,
            data = mapOf(EI.user to "fdfu-scoped@acme.test", GDF.entries to siteAuditEntries()),
        )
    }
})
