package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.cfact.CFACT
import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.MNU
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
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
 * Editing a client's home menu (issue #919): a rename and a hide for acme land in its stored `copy` config as drafts
 * (acme has a sandbox, issue #994), and once published show in the items listing and in what an acme user is served,
 * leaving globex alone; a show puts a withdrawn item on offer for globex -- which has no sandbox, so at once -- under
 * a condition the shipped menu knows, and a made-up condition is refused; a reset restores the shipped item; and the
 * scoping is the client overview's.
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
        acme.getValue(HMENU.workflows)[MNU.condition] shouldBe "${CFACTS.loggedIn},${CFACTS.app}"
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
        renamed[CPY.mode] shouldBe EDM.draft
        val hidden = edit(SC.acme, HMENU.docs, MNU.visibility to MNU.hide)
        hidden[MNU.condition] shouldBe CFACT.neverName
        // Drafts: an acme user still sees the shipped menu, until they are published.
        servedMenu(acmeUser)[HMENU.profile] shouldBe "Profile"
        publishAcme(admin)

        val served = servedMenu(acmeUser)
        served[HMENU.profile] shouldBe "My account"
        served.keys shouldNotContain HMENU.docs
        // Both changes in one config, one item each; the rename kept nothing else of the item.
        val acme = items(SC.acme)
        acme.getValue(HMENU.profile)[CPY.stored] shouldBe true
        acme.getValue(HMENU.docs)[MNU.baseCondition] shouldBe CFACTS.app
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
        // The refusal says what the item may be shown to, each by its name.
        refused[EP.errorMessage].toOptStr().orEmpty().let {
            it shouldContain "not an audience the menu item '${HMENU.workflows}' may be shown to"
            it shouldContain "Everyone signed in (${CFACTS.loggedIn},${CFACTS.app})"
        }
        servedMenu(globexUser).keys shouldNotContain HMENU.workflows

        val shown = edit(SC.globex, HMENU.workflows, MNU.visibility to MNU.show, MNU.condition to "${CFACTS.loggedIn},${CFACTS.app}")
        shown[MNU.condition] shouldBe "${CFACTS.loggedIn},${CFACTS.app}"
        shown[MNU.audience] shouldBe "Everyone signed in"
        servedMenu(globexUser).keys shouldContain HMENU.workflows
        // A show needs its condition; an edit that asks for nothing is refused too.
        admin.expectError(EXC.badInput, MNU.setPath, data = mapOf(COV.client to SC.globex, COV.itemId to HMENU.docs, MNU.visibility to MNU.show))
        admin.expectError(EXC.badInput, MNU.setPath, data = mapOf(COV.client to SC.globex, COV.itemId to HMENU.docs))
        admin.expectError(EXC.notFound, MNU.setPath, data = mapOf(COV.client to SC.globex, COV.itemId to "noSuchItem", MNU.label to "x"))
        // The item is the subject: one the menu does not have is said first, whatever else is wrong with the edit.
        admin.expectError(EXC.notFound, MNU.setPath, data = mapOf(COV.client to SC.globex, COV.itemId to "noSuchItem"))
        admin.expectError(EXC.notFound, MNU.setPath, data = mapOf(COV.client to SC.globex, COV.itemId to "noSuchItem", MNU.visibility to MNU.show))
        // A group cannot be hidden: the app bar would take every child with it, Log out included.
        val group = admin.expectError(EXC.badInput, MNU.setPath, data = mapOf(COV.client to SC.globex, COV.itemId to HMENU.account, MNU.visibility to MNU.hide))
        group[EP.errorMessage].toOptStr().orEmpty() shouldContain HMENU.logout
        servedMenu(globexUser).keys shouldContain HMENU.logout
    }

    // Who an item is shown to is chosen by name (issue #1094): the listing names each condition's audience and says
    // which audiences the item may be shown to, and a show accepts exactly those.
    "the listing names each audience and what an item may be shown to; a show accepts those and no other" {
        fun conditionsOffered(item: Map<String, Any?>) = item[MNU.audiences].toJsonListOfMaps().map { it[MNU.condition] }
        val listed = items(SC.globex)
        val docs = listed.getValue(HMENU.docs)
        docs[MNU.baseAudience] shouldBe "Everyone"
        docs[MNU.audience] shouldBe "Everyone"
        // Each choice is a condition and its name, the condition being what a show sends.
        docs[MNU.audiences].toJsonListOfMaps().first() shouldBe mapOf(MNU.condition to CFACTS.app, MNU.name to "Everyone")
        // The deployment's own audiences are not handed to an item that did not ship with one...
        conditionsOffered(docs) shouldNotContain CFACTS.isDeploymentOperator
        conditionsOffered(docs) shouldNotContain "${CFACTS.hasAdminLevel},${CFACTS.app}"
        // ...while the item that did may go back to it, and its row names it.
        val users = listed.getValue(HMENU.users)
        users[MNU.baseAudience] shouldBe "Anyone at the administrator level"
        conditionsOffered(users) shouldContain "${CFACTS.hasAdminLevel},${CFACTS.app}"
        // A withdrawn item has no audience to name.
        items(SC.acme).getValue(HMENU.cfactReference).let {
            it.containsKey(MNU.audience) shouldBe false
            it[MNU.baseAudience] shouldBe "Operators and administrators"
        }

        // The write holds to the same list: another item's audience is refused, the item's own is taken.
        val foreign = admin.expectError(
            EXC.badInput, MNU.setPath,
            data = mapOf(COV.client to SC.globex, COV.itemId to HMENU.docs, MNU.visibility to MNU.show, MNU.condition to "${CFACTS.hasAdminLevel},${CFACTS.app}"),
        )
        foreign[EP.errorMessage].toOptStr().orEmpty() shouldContain "not an audience the menu item '${HMENU.docs}' may be shown to"
        edit(SC.globex, HMENU.users, MNU.visibility to MNU.hide).containsKey(MNU.audience) shouldBe false
        // Hidden, it is still offered the audience it shipped under: what it may go back to is the shipped item's.
        conditionsOffered(items(SC.globex).getValue(HMENU.users)) shouldContain "${CFACTS.hasAdminLevel},${CFACTS.app}"
        edit(SC.globex, HMENU.users, MNU.visibility to MNU.show, MNU.condition to "${CFACTS.hasAdminLevel},${CFACTS.app}")[MNU.audience] shouldBe
            "Anyone at the administrator level"
        admin.postData(MNU.resetPath, mapOf(COV.client to SC.globex, COV.itemId to HMENU.users))[MNU.audience] shouldBe "Anyone at the administrator level"
    }

    "a draft this node does not run is not a change the listing reports" {
        // An unpublished revision of a new globex config renaming Profile, written through the bundle API, which does
        // not reload: nothing the client's people see has changed, and the listing says so.
        admin.postData(
            ACEP.bundleWrite,
            mapOf(CFEP.client to SC.globex, CFEP.name to "menuDraft", CFEP.namespaceField to clientNamespace(SC.globex),
                CFEP.slots to mapOf(CCT.uiBlockDef to listOf(mapOf(CCT.blockId to HMENU.block, CCT.content to mapOf(HFLD.menu to listOf(mapOf(HFLD.id to HMENU.profile, HFLD.label to "Draft profile"))))))),
        )
        val profile = items(SC.globex).getValue(HMENU.profile)
        profile[MNU.label] shouldBe "Profile"
        profile[CPY.stored] shouldBe false
        servedMenu(globexUser)[HMENU.profile] shouldBe "Profile"
    }

    "a reset restores the shipped item; one with nothing stored is refused" {
        val reset = admin.postData(MNU.resetPath, mapOf(COV.client to SC.acme, COV.itemId to HMENU.docs))
        reset[CPY.stored] shouldBe false
        reset[MNU.condition] shouldBe CFACTS.app
        servedMenu(acmeUser).keys shouldNotContain HMENU.docs
        publishAcme(admin)
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
        publishAcme(scoped, acrossClients = false)
        servedMenu(acmeUser)[HMENU.profile] shouldBe "Me"
        scoped.expectError(EXC.notAuthorized, MNU.setPath, data = mapOf(COV.client to SC.globex, COV.itemId to HMENU.profile, MNU.label to "Nope"))
        servedMenu(globexUser)[HMENU.profile] shouldBe "Profile"
    }

    // Last, since it reloads globex. An item a client adds has nothing shipped behind it (issue #1094): the listing
    // must not read "no shipped item" as "shipped with no condition", which is everyone.
    "an item the client added is listed as its own, with no shipped audience and nothing it may be shown to" {
        val extra = "siteNews"
        admin.postData(
            ACEP.bundleWrite,
            mapOf(CFEP.client to SC.globex, CFEP.name to "menuExtra", CFEP.namespaceField to clientNamespace(SC.globex),
                CFEP.slots to mapOf(CCT.uiBlockDef to listOf(mapOf(CCT.blockId to HMENU.block, CCT.content to mapOf(HFLD.menu to listOf(mapOf(HFLD.id to extra, HFLD.label to "Site news"))))))),
        )
        admin.postData(ACEP.bundlePublish, mapOf(CFEP.client to SC.globex, CFEP.name to "menuExtra"))[CFEP.published] shouldBe true
        admin.postData(ACEP.reload, mapOf(CFEP.client to SC.globex))
        servedMenu(globexUser)[extra] shouldBe "Site news"

        val listed = items(SC.globex)
        val added = listed.getValue(extra)
        added[COV.added] shouldBe true
        added.containsKey(COV.baseLabel) shouldBe false
        added.containsKey(MNU.baseAudience) shouldBe false
        added[MNU.audiences].toJsonListOfMaps() shouldBe emptyList()
        // Its own condition is still named: written with none, it is drawn for everyone.
        added[MNU.audience] shouldBe "Everyone, edge nodes included"
        // Nothing offered, because nothing would be taken: the write changes shipped items only.
        admin.expectError(EXC.notFound, MNU.setPath, data = mapOf(COV.client to SC.globex, COV.itemId to extra, MNU.label to "News"))
        // A shipped item with no condition did ship for everyone, and says so.
        listed.getValue(HMENU.catalog).let {
            it[COV.added] shouldBe false
            it[MNU.baseAudience] shouldBe "Everyone, edge nodes included"
        }
    }
})
