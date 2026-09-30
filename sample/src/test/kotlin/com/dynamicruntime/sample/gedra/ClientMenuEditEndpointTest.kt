package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.cfact.CFACT
import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.MNU
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Editing a client's home menu (issue #919): a rename and a hide for acme land in its stored `copy` config, show in
 * the items listing and in what an acme user is served, and leave globex alone; a show puts a withdrawn item on offer
 * for globex under a condition the shipped menu knows, and a made-up condition is refused; a reset restores the
 * shipped item; and the scoping is the client overview's.
 */
class ClientMenuEditEndpointTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "clientMenuEdit", "clientMenuEditTest", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )
    val admin = TestUser.createFullAdmin(cxt, "menu-admin@example.com")
    val acmeUser = TestUser.create(cxt, "menu-user@acme.test", userClient = SC.acme)
    val globexUser = TestUser.create(cxt, "menu-user@globex.test", userClient = SC.globex)

    fun items(client: String): Map<String, Map<String, Any?>> =
        admin.getItems(MNU.itemsPath, mapOf(COV.client to client)).associateBy { it[COV.itemId] as String }

    /** The menu a user is served: id to label. */
    fun servedMenu(user: TestUser): Map<String, String> =
        user.getData(HEP.homeUiConfig)[UIC.state].toJsonMapOrEmpty()[HFLD.menu].toJsonListOfMaps()
            .associate { it[HFLD.id].toOptStr().orEmpty() to it[HFLD.label].toOptStr().orEmpty() }

    fun edit(client: String, itemId: String, vararg fields: Pair<String, Any?>): Map<String, Any?> =
        admin.postData(MNU.setPath, mapOf(COV.client to client, COV.itemId to itemId) + fields.toMap())

    "the items listing says what the shipped menu draws and what the client changed" {
        val acme = items(SC.acme)
        acme.getValue(HMENU.profile)[COV.baseLabel] shouldBe "Profile"
        acme.getValue(HMENU.profile)[MNU.label] shouldBe "Profile"
        acme.getValue(HMENU.profile)[CPY.stored] shouldBe false
        // acme's source config shows Workflows and hides Client facts: the effective condition says so, and neither is stored.
        acme.getValue(HMENU.workflows)[MNU.baseCondition] shouldBe CFACT.neverName
        acme.getValue(HMENU.workflows)[MNU.condition] shouldBe "${CFACTS.loggedIn},${BOOT.app}"
        acme.getValue(HMENU.cfactReference)[MNU.condition] shouldBe CFACT.neverName
        acme.getValue(HMENU.cfactReference)[CPY.stored] shouldBe false
        items(SC.globex).getValue(HMENU.workflows)[MNU.condition] shouldBe CFACT.neverName
    }

    "a rename and a hide land in the copy config and reach an acme user's menu; globex is untouched" {
        servedMenu(acmeUser)[HMENU.profile] shouldBe "Profile"
        servedMenu(acmeUser).keys shouldContain HMENU.docs

        val renamed = edit(SC.acme, HMENU.profile, MNU.label to "My account")
        renamed[COV.configName] shouldBe CPY.copyConfigName
        renamed[MNU.label] shouldBe "My account"
        renamed[CPY.stored] shouldBe true
        val hidden = edit(SC.acme, HMENU.docs, MNU.visibility to MNU.hide)
        hidden[MNU.condition] shouldBe CFACT.neverName

        val served = servedMenu(acmeUser)
        served[HMENU.profile] shouldBe "My account"
        served.keys shouldNotContain HMENU.docs
        // Both changes in one config, one item each; the rename kept nothing else of the item.
        val acme = items(SC.acme)
        acme.getValue(HMENU.profile)[CPY.stored] shouldBe true
        acme.getValue(HMENU.docs)[MNU.baseCondition] shouldBe BOOT.app
        admin.getItems(ACEP.bundles, mapOf(CFEP.client to SC.acme)).single { it[CFEP.name] == CPY.copyConfigName }[CFEP.published] shouldBe true
        // The overrides report now lists both as the client's.
        val blocks = admin.getItem(UADEP.clientOverrides, mapOf(COV.client to SC.acme))[COV.blocks].toJsonListOrEmpty()
            .map { it.toJsonMapOrEmpty() }.filter { it[COV.blockId] == HMENU.block }.associateBy { it[COV.itemId] }
        blocks.getValue(HMENU.docs)[COV.hidden] shouldBe true
        blocks.keys shouldContain HMENU.profile
        // globex sees the shipped menu.
        servedMenu(globexUser)[HMENU.profile] shouldBe "Profile"
        servedMenu(globexUser).keys shouldContain HMENU.docs
    }

    "a show puts a withdrawn item on offer under a condition the shipped menu knows; a made-up one is refused" {
        servedMenu(globexUser).keys shouldNotContain HMENU.workflows
        val refused = admin.expectError(
            EXC.badInput, MNU.setPath,
            data = mapOf(COV.client to SC.globex, COV.itemId to HMENU.workflows, MNU.visibility to MNU.show, MNU.condition to "isChief"),
        )
        refused[EP.errorMessage].toOptStr().orEmpty() shouldContain "not a condition the shipped menu draws for"
        servedMenu(globexUser).keys shouldNotContain HMENU.workflows

        edit(SC.globex, HMENU.workflows, MNU.visibility to MNU.show, MNU.condition to "${CFACTS.loggedIn},${BOOT.app}")[MNU.condition] shouldBe "${CFACTS.loggedIn},${BOOT.app}"
        servedMenu(globexUser).keys shouldContain HMENU.workflows
        // A show needs its condition; an edit that asks for nothing is refused too.
        admin.expectError(EXC.badInput, MNU.setPath, data = mapOf(COV.client to SC.globex, COV.itemId to HMENU.docs, MNU.visibility to MNU.show))
        admin.expectError(EXC.badInput, MNU.setPath, data = mapOf(COV.client to SC.globex, COV.itemId to HMENU.docs))
        admin.expectError(EXC.notFound, MNU.setPath, data = mapOf(COV.client to SC.globex, COV.itemId to "noSuchItem", MNU.label to "x"))
    }

    "a reset restores the shipped item; one with nothing stored is refused" {
        val reset = admin.postData(MNU.resetPath, mapOf(COV.client to SC.acme, COV.itemId to HMENU.docs))
        reset[CPY.stored] shouldBe false
        reset[MNU.condition] shouldBe BOOT.app
        servedMenu(acmeUser).keys shouldContain HMENU.docs
        // The rename in the same entry survived the reset of the other item.
        servedMenu(acmeUser)[HMENU.profile] shouldBe "My account"
        admin.expectError(EXC.badInput, MNU.resetPath, data = mapOf(COV.client to SC.acme, COV.itemId to HMENU.docs))
        // An item acme's *source* config changes is not data's to reset.
        val refused = admin.expectError(EXC.badInput, MNU.resetPath, data = mapOf(COV.client to SC.acme, COV.itemId to HMENU.cfactReference))
        refused[EP.errorMessage].toOptStr().orEmpty() shouldContain "source code or the shipped menu"
    }

    "a client-scoped administrator edits their own client's menu and may not name another" {
        val scoped = TestUser.create(cxt, "menu-scoped@acme.test", level = ROLE.admin, userClient = SC.acme)
        scoped.getItems(MNU.itemsPath).map { it[COV.itemId] } shouldContain HMENU.profile
        scoped.postData(MNU.setPath, mapOf(COV.itemId to HMENU.profile, MNU.label to "Me"))[MNU.label] shouldBe "Me"
        servedMenu(acmeUser)[HMENU.profile] shouldBe "Me"
        scoped.expectError(EXC.notAuthorized, MNU.setPath, data = mapOf(COV.client to SC.globex, COV.itemId to HMENU.profile, MNU.label to "Nope"))
        servedMenu(globexUser)[HMENU.profile] shouldBe "Profile"
    }
})
