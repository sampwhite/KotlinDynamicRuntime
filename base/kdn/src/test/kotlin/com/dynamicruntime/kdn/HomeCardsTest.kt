package com.dynamicruntime.kdn

import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.home.HCARD
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.home.homeCard
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.uiblock.UiBlockService
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * The home cards (issue #1636): exactly which a caller gets, decided by cfacts as the menu is, the three facts about
 * where the caller is that cards may be offered by, and a client's overlay hiding and adding one. Over the shared
 * instance; the assistant module is not loaded here, so its cards are not among them (`AssistantSelfServeTest`).
 */
class HomeCardsTest : StringSpec({

    val cxt = TestInstances.default("homeCards")

    /** The cards out of a home config's `results`. */
    fun cardsIn(results: Map<String, Any?>): List<Map<String, Any?>> =
        results[UIC.state].toJsonMapOrEmpty()[HCARD.cards].toJsonListOfMaps()

    fun cardsOf(user: TestUser) = cardsIn(user.getData(HEP.homeUiConfig))

    fun idsOf(user: TestUser) = cardsOf(user).map { it[HFLD.id] }

    /** "Waiting on you" as a signed-in caller is sent it where a step can wait (issue #1637): its kind's body says what waits. */
    val waitingCard = mapOf(
        HFLD.id to HCARD.waiting,
        HCARD.title to "Waiting on you",
        HCARD.text to "Forms with a step that is yours to take.",
        HCARD.jumpLabel to "Waiting on you",
        HCARD.kind to HCARD.kindWaitingOnYou,
    )

    /** "Unpublished drafts" as a client's administrator is sent it where the client has a sandbox (issue #1638). */
    val draftsCard = mapOf(
        HFLD.id to HCARD.drafts,
        HCARD.title to "Unpublished drafts",
        HCARD.text to "Changes to your client's configuration are tried in its sandbox, and reach the client once published.",
        HCARD.jumpLabel to "Unpublished drafts",
        HCARD.kind to HCARD.kindUnpublishedDrafts,
    )

    "each kind of caller gets the cards for what they do, and nobody else's" {
        // `public` and `hub` declare no workflow whose step can wait, so nobody here is sent "Waiting on you" (issue
        // #1637); a client that does is below.
        idsOf(TestUser.create(cxt, "cards-member@example.com")) shouldBe emptyList()
        idsOf(TestUser.create(cxt, "cards-public@example.com", level = ROLE.admin, userClient = CL.public)) shouldBe emptyList()
        idsOf(TestUser.create(cxt, "cards-admin@example.com", level = ROLE.admin)) shouldBe listOf(HCARD.administer)
        idsOf(TestUser.create(cxt, "cards-editor@example.com", capabilities = listOf(ROLE.editor), userClient = CL.hub)) shouldBe
            listOf(HCARD.edit)
        idsOf(TestUser.createOperator(cxt, "cards-ops@example.com")) shouldBe listOf(HCARD.operate)
        // Every client's administrator oversees them all, and operates the deployment too; "My client" is not theirs.
        idsOf(TestUser.createFullAdmin(cxt, "cards-root@example.com")) shouldBe listOf(HCARD.oversee, HCARD.operate)
        // A visitor who has not signed in gets none.
        cardsIn(TestHttpClient(cxt.instanceConfig).sendJsonGetRequest(HEP.homeUiConfig)[EP.results].toJsonMapOrEmpty()) shouldBe
            emptyList()
    }

    "a card is sent whole, its condition and order left behind" {
        cardsOf(TestUser.create(cxt, "cards-shape@example.com", level = ROLE.admin)) shouldBe listOf(
            mapOf(
                HFLD.id to HCARD.administer,
                HCARD.title to "Your client",
                HCARD.text to "Set up your client and its home page, add the people who use it, and run its reports.",
                HCARD.jumpLabel to "Your client",
                HCARD.links to listOf(
                    mapOf(HCARD.linkLabel to "My client", UIB.action to HMENU.pageClients),
                    mapOf(HCARD.linkLabel to "Users", UIB.action to HMENU.pageUsers),
                    mapOf(HCARD.linkLabel to "Reports", UIB.action to HMENU.pageReports),
                ),
            ),
        )
    }

    "the facts about where a caller is: signed in to public, in a self-serve client, in a client with a sandbox" {
        val registry = SchemaService.get(cxt).cfactsFor(null)
        fun factsIn(client: String, loggedIn: Boolean = true): Set<String> {
            val sub = cxt.mkSubContext("cardFacts", client)
            sub.userProfile = if (loggedIn) UserProfile(authId = "someone", roles = setOf(ROLE.user), client = client) else UserProfile()
            return registry.assemble(sub)
        }
        // Signed in to public; not a visitor who has not signed in, though they act there too.
        factsIn(CL.public) shouldContain CFACTS.publicMember
        factsIn(CL.public, loggedIn = false) shouldNotContain CFACTS.publicMember
        factsIn(CL.hub) shouldNotContain CFACTS.publicMember

        // A person's own client is self-serve and has a sandbox, and so does its sandbox; `hub` is neither.
        val person = TestUser.create(cxt, "cards-maker@self1636.test")
        val made = person.postData(AEP.createOwnClient, mapOf(CLD.name to "Cards"))[CLD.client].toOptStr().shouldNotBeNull()
        for (client in listOf(made, sandboxOf(made))) {
            factsIn(client) shouldContain CFACTS.selfServeClient
            factsIn(client) shouldContain CFACTS.clientHasSandbox
        }
        factsIn(CL.hub) shouldNotContain CFACTS.selfServeClient
        factsIn(CL.hub) shouldNotContain CFACTS.clientHasSandbox
        // Its administrator is offered the card for administering one client -- and, since it starts with a core
        // details workflow, whose step can wait, "Waiting on you" first (issue #1637); and, since it has a sandbox,
        // "Unpublished drafts" next (issue #1638). Each of those two draws only when its read finds something.
        factsIn(made) shouldContain CFACTS.clientHasWaitingWorkflows
        factsIn(CL.hub) shouldNotContain CFACTS.clientHasWaitingWorkflows
        cardsOf(person).first() shouldBe waitingCard
        cardsOf(person)[1] shouldBe draftsCard
        idsOf(person) shouldBe listOf(HCARD.waiting, HCARD.drafts, HCARD.administer)
    }

    "resolving several blocks at once gives each as resolving it alone does" {
        val admin = cxt.mkSubContext("cardsResolve", CL.hub)
        admin.userProfile = UserProfile(authId = "someone", roles = setOf(ROLE.user, ROLE.admin), client = CL.hub)
        val service = UiBlockService.get(cxt)
        val both = service.resolveAll(admin, listOf(HMENU.block, HCARD.block, "noSuchBlock1636"))
        both[HMENU.block] shouldBe service.resolve(admin, HMENU.block)
        both[HCARD.block] shouldBe service.resolve(admin, HCARD.block)
        both["noSuchBlock1636"] shouldBe null
    }

    "a client hides a card and adds one of its own, as it changes its menu" {
        val client = "cards1636"
        val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            uiBlockOverlay(HCARD.block) {
                items(HCARD.cards) {
                    homeCard(HCARD.administer, cfactExpression = "#never")
                    homeCard(
                        "welcome1636", "Welcome", "Start with [the documents](/docs/start-here.md).",
                        cfactExpression = CFACTS.loggedIn, displayOrder = 10,
                    )
                }
            }
        }
        GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("cardsWrite", client).also { it.userId = 16361L }, config)
        GedraConfigReload.reloadClient(cxt, client)

        val admin = TestUser.create(cxt, "admin@cards1636.test", level = ROLE.admin, userClient = client)
        cardsOf(admin) shouldBe listOf(
            mapOf(HFLD.id to "welcome1636", HCARD.title to "Welcome", HCARD.text to "Start with [the documents](/docs/start-here.md)."),
        )
        // Another client's administrator is unaffected.
        idsOf(TestUser.create(cxt, "cards-other@example.com", level = ROLE.admin)) shouldBe listOf(HCARD.administer)
    }
})
