package com.dynamicruntime.kdn

import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.cfact.CFD
import com.dynamicruntime.common.cfact.CFGRP
import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The cfacts a real node declares, and the reference endpoint that reports them (issues #455, #488).
 *
 * Boot-level rather than unit, because what it is checking is that the registry a **running** node holds is
 * the one the core component declared -- the seam a unit test builds by hand and so cannot see. The boot-role
 * assertions are the sharpest ones: they show the registry knows what this node *is*, which is the whole
 * reason one set of frontend data can serve an application and an edge.
 */
class CFactRegistryEndpointTest : StringSpec({

    val cxt = Startup.mkTestBootCxt("cfacts", "cfactsTest")

    fun reference(user: TestUser): String =
        user.getItem(CFD.cfactsPath)[CFD.markdown].toOptStr().orEmpty()

    "the reference names every cfact the node knows, in groups" {
        val doc = reference(TestUser.create(cxt, "cfact-admin@example.com", level = ROLE.admin))

        // Both boot roles, on either kind of node: an edge does not *remove* the application's items, it
        // fails to match them -- so `edge` has to be a name an application knows even though it is never
        // present there.
        doc shouldContain BOOT.app
        doc shouldContain BOOT.edge
        doc shouldContain CFACTS.loggedIn
        doc shouldContain CFACTS.anonymous
        doc shouldContain CFACTS.hasOperatorLevel
        doc shouldContain CFACTS.hasAdminLevel
        doc shouldContain CFACTS.isClientOperator
        // Grouped, so a reader sees sections rather than one wall -- the group headings are in the document.
        doc shouldContain CFGRP.caller
        doc shouldContain CFGRP.node
    }

    "the reference says whether each cfact is present for the caller" {
        // The live half a static list could never carry (issue #488). An administrator on an application node:
        // `hasAdminLevel` fires, `edge` does not, and the document says so beside each name.
        val doc = reference(TestUser.create(cxt, "cfact-present@example.com", level = ROLE.admin))
        doc shouldContain "`${CFACTS.hasAdminLevel}`\n\n**For you now:** present"
        doc shouldContain "`${BOOT.edge}`\n\n**For you now:** absent"
    }

    "what a node assembles says which node it is" {
        // The other half of the registry, and the half a document cannot show directly: the *sources*. This node
        // is an ordinary application, so exactly one boot-role cfact is present.
        val registry = SchemaService.get(cxt).cfactsFor(null)
        val anonymous = cxt.mkSubContext("cfactAnon")
        anonymous.userProfile = UserProfile()
        val present = registry.assemble(anonymous)
        present shouldContain BOOT.app
        present shouldNotContain BOOT.edge
        // ...and this caller has no identity, so the positive form of "not logged in" is the one that fires.
        present shouldContain CFACTS.anonymous
        present shouldNotContain CFACTS.loggedIn
        present shouldNotContain CFACTS.hasAdminLevel
    }

    "an administrator satisfies the operator cfact without holding the role" {
        // What makes this a ladder question rather than a membership one -- and why it is named for the *level*
        // rather than `isOperator`, which said neither axis clearly.
        val registry = SchemaService.get(cxt).cfactsFor(null)
        val admin = cxt.mkSubContext("cfactAdmin")
        admin.userProfile = UserProfile(authId = "someone", roles = setOf(ROLE.user, ROLE.admin))
        val present = registry.assemble(admin)
        present shouldContain CFACTS.hasOperatorLevel
        present shouldContain CFACTS.hasAdminLevel
        present shouldContain CFACTS.loggedIn
        present shouldNotContain CFACTS.anonymous
    }

    "the reference is reachable by an operator or admin, not a plain user" {
        // Moved from `clientAdmin` to `clientOperator` (issue #488): an operator now reads it, which the
        // admin-only listing refused, while a plain user still cannot. The change of who is admitted is the
        // point of the move, so both halves are asserted.
        reference(TestUser.create(cxt, "cfact-op@example.com", level = ROLE.operator)) shouldContain BOOT.app
        TestUser.create(cxt, "cfact-user@example.com").expectError(EXC.notAuthorized, CFD.cfactsPath)
    }
})
