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
import io.kotest.matchers.shouldNotBe

/**
 * The cfacts a real node declares, and the endpoint that reports them (issue #455).
 *
 * Boot-level rather than unit, because what it is checking is that the registry a **running** node holds is
 * the one the core component declared -- the seam a unit test builds by hand and so cannot see. The boot-role
 * assertions are the sharpest ones: they show the registry knows what this node *is*, which is the whole
 * reason one set of frontend data can serve an application and an edge.
 */
class CFactRegistryEndpointTest : StringSpec({

    val cxt = Startup.mkTestBootCxt("cfacts", "cfactsTest")

    fun listed(user: TestUser): Map<String, Map<String, Any?>> =
        user.getItems(CFD.cfactsPath).associateBy { it[CFD.name].toOptStr().orEmpty() }

    "a booted node reports every cfact it knows, with a group and a description" {
        val opal = TestUser.create(cxt, "cfact-admin@example.com", level = ROLE.admin)
        val byName = listed(opal)

        // Both boot roles, on either kind of node: an edge does not *remove* the application's items, it
        // fails to match them -- so `edge` has to be a name an application can parse even though it is never
        // present there.
        byName.keys shouldContain BOOT.app
        byName.keys shouldContain BOOT.edge
        byName.keys shouldContain CFACTS.loggedIn
        byName.keys shouldContain CFACTS.anonymous
        byName.keys shouldContain CFACTS.hasOperatorLevel
        byName.keys shouldContain CFACTS.hasAdminLevel

        // A declaration with no description is one somebody has to read the source to use, which is the state
        // the discovery endpoint exists to replace.
        byName.getValue(CFACTS.hasAdminLevel)[CFD.group] shouldBe CFGRP.caller
        byName.getValue(CFACTS.hasAdminLevel)[CFD.description].toOptStr() shouldNotBe null
        byName.getValue(BOOT.app)[CFD.group] shouldBe CFGRP.node
    }

    "the listing is ordered by group, so a page can present it in sections" {
        val opal = TestUser.create(cxt, "cfact-admin2@example.com", level = ROLE.admin)
        val groups = opal.getItems(CFD.cfactsPath).map { it[CFD.group].toOptStr().orEmpty() }
        // Decided by the endpoint rather than by each reader: two pages sorting the same list differently is
        // the kind of difference nobody reports.
        groups shouldBe groups.sorted()
    }

    "what a node assembles says which node it is" {
        // The other half of the registry, and the half the endpoint cannot show: the *sources*. This node is
        // an ordinary application, so exactly one boot-role cfact is present.
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

    "the listing is an administrator's, not an ordinary user's -- and not an operator's" {
        // `clientAdmin` rather than `operator`, which is a real narrowing as well as a change of meaning: an
        // administrator confined to one client reads it (that is who authors configuration), and somebody
        // running the deployment no longer does unless they are also an administrator. Both halves asserted,
        // because the second is the cost of the move and would otherwise be discovered by an operator.
        TestUser.create(cxt, "cfact-user@example.com").expectError(EXC.notAuthorized, CFD.cfactsPath)
        TestUser.create(cxt, "cfact-op@example.com", level = ROLE.operator)
            .expectError(EXC.notAuthorized, CFD.cfactsPath)
    }
})
